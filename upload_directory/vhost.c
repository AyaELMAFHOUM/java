/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @file  vhost.c
 * @brief functions pertaining to virtual host addresses
 *        (configuration and run-time)
 */

#include "apr.h"
#include "apr_strings.h"
#include "apr_lib.h"
#include "apr_version.h"

#define APR_WANT_STRFUNC
#include "apr_want.h"

#include "ap_config.h"
#include "httpd.h"
#include "http_config.h"
#include "http_log.h"
#include "http_vhost.h"
#include "http_protocol.h"
#include "http_core.h"
#include "http_main.h"

#if APR_HAVE_ARPA_INET_H
#include <arpa/inet.h>
#endif

/* we know core's module_index is 0 */
#undef APLOG_MODULE_INDEX
#define APLOG_MODULE_INDEX AP_CORE_MODULE_INDEX

/*
 * After all the definitions there's an explanation of how it's all put
 * together.
 */

/* meta-list of name-vhosts.  Each server_rec can be in possibly multiple
 * lists of name-vhosts.
 */
typedef struct name_chain name_chain;
struct name_chain {
    name_chain *next;
    server_addr_rec *sar;       /* the record causing it to be in
                                 * this chain (needed for port comparisons) */
    server_rec *server;         /* the server to use on a match */
};

/* meta-list of ip addresses.  Each server_rec can be in possibly multiple
 * hash chains since it can have multiple ips.
 */
typedef struct ipaddr_chain ipaddr_chain;
struct ipaddr_chain {
    ipaddr_chain *next;
    server_addr_rec *sar;       /* the record causing it to be in
                                 * this chain (need for both ip addr and port
                                 * comparisons) */
    server_rec *server;         /* the server to use if this matches */
    name_chain *names;          /* if non-NULL then a list of name-vhosts
                                 * sharing this address */
    name_chain *initialnames;   /* no runtime use, temporary storage of first
                                 * NVH'es names */
};

/* This defines the size of the hash table used for hashing ip addresses
 * of virtual hosts.  It must be a power of two.
 */
#ifndef IPHASH_TABLE_SIZE
#define IPHASH_TABLE_SIZE 256
#endif

/* A (n) bucket hash table, each entry has a pointer to a server rec and
 * a pointer to the other entries in that bucket.  Each individual address,
 * even for virtualhosts with multiple addresses, has an entry in this hash
 * table.  There are extra buckets for _default_, and name-vhost entries.
 *
 * Note that after config time this is constant, so it is thread-safe.
 */
static ipaddr_chain *iphash_table[IPHASH_TABLE_SIZE];

/* dump out statistics about the hash function */
/* #define IPHASH_STATISTICS */

/* list of the _default_ servers */
static ipaddr_chain *default_list;

/* whether a config error was seen */
static int config_error = 0;

/* config check function */
static int vhost_check_config(apr_pool_t *p, apr_pool_t *plog,
                              apr_pool_t *ptemp, server_rec *s);

/*
 * How it's used:
 *
 * The ip address determines which chain in iphash_table is interesting, then
 * a comparison is done down that chain to find the first ipaddr_chain whose
 * sar matches the address:port pair.
 *
 * If that ipaddr_chain has names == NULL then you're done, it's an ip-vhost.
 *
 * Otherwise it's a name-vhost list, and the default is the server in the
 * ipaddr_chain record.  We tuck away the ipaddr_chain record in the
 * conn_rec field vhost_lookup_data.  Later on after the headers we get a
 * second chance, and we use the name_chain to figure out what name-vhost
 * matches the headers.
 *
 * If there was no ip address match in the iphash_table then do a lookup
 * in the default_list.
 *
 * How it's put together ... well you should be able to figure that out
 * from how it's used.  Or something like that.
 */


/* called at the beginning of the config */
AP_DECLARE(void) ap_init_vhost_config(apr_pool_t *p)
{
    memset(iphash_table, 0, sizeof(iphash_table));
    default_list = NULL;
    ap_hook_check_config(vhost_check_config, NULL, NULL, APR_HOOK_MIDDLE);
}


/*
 * Parses a host of the form <address>[:port]
 * paddr is used to create a list in the order of input
 * **paddr is the ->next pointer of the last entry (or s->addrs)
 * *paddr is the variable used to keep track of **paddr between calls
 * port is the default port to assume
 */
static const char *get_addresses(apr_pool_t *p, const char *w_,
                                 server_addr_rec ***paddr,
                                 apr_port_t default_port)
{
    apr_sockaddr_t *my_addr;
    server_addr_rec *sar;
    char *w, *host, *scope_id;
    int wild_port;
    apr_size_t wlen;
    apr_port_t port;
    apr_status_t rv;

    if (*w_ == '\0')
        return NULL;

    wlen = strlen(w_);                   /* wlen must be > 0 at this point */
    w = apr_pstrmemdup(p, w_, wlen);
    /* apr_parse_addr_port() doesn't understand ":*" so handle that first. */
    wild_port = 0;
    if (w[wlen - 1] == '*') {
        if (wlen < 2) {
            wild_port = 1;
        }
        else if (w[wlen - 2] == ':') {
            w[wlen - 2] = '\0';
            wild_port = 1;
        }
    }
    rv = apr_parse_addr_port(&host, &scope_id, &port, w, p);
    /* If the string is "80", apr_parse_addr_port() will be happy and set
     * host to NULL and port to 80, so watch out for that.
     */
    if (rv != APR_SUCCESS) {
        return "The address or port is invalid";
    }
    if (!host) {
        return "Missing address for VirtualHost";
    }
#if !APR_VERSION_AT_LEAST(1,7,0)
    if (scope_id) {
        return apr_pstrcat(p,
                           "Scope ID in address '", w,
                           "' not supported with APR " APR_VERSION_STRING,
                           NULL);
    }
#endif
    if (!port && !wild_port) {
        port = default_port;
    }

    if (strcmp(host, "*") == 0 || strcasecmp(host, "_default_") == 0) {
        rv = apr_sockaddr_info_get(&my_addr, NULL, APR_UNSPEC, port, 0, p);
        if (rv) {
            return "Could not determine a wildcard address ('0.0.0.0') -- "
                "check resolver configuration.";
        }
    }
    else {
        rv = apr_sockaddr_info_get(&my_addr, host, APR_UNSPEC, port, 0, p);
        if (rv != APR_SUCCESS) {
            ap_log_error(APLOG_MARK, APLOG_ERR, rv, NULL, APLOGNO(00547)
                "Could not resolve host name %s -- ignoring!", host);
            return NULL;
        }
#if APR_VERSION_AT_LEAST(1,7,0)
        if (scope_id) {
            rv = apr_sockaddr_zone_set(my_addr, scope_id);
            if (rv) {
                ap_log_error(APLOG_MARK, APLOG_ERR, rv, NULL, APLOGNO(10103)
                             "Could not set scope ID %s for %pI -- ignoring!",
                             scope_id, my_addr);
                return NULL;
            }
        }
#endif
    }

    /* Remember all addresses for the host */

    do {
        sar = apr_pcalloc(p, sizeof(server_addr_rec));
        **paddr = sar;
        *paddr = &sar->next;
        sar->host_addr = my_addr;
        sar->host_port = port;
        sar->virthost = host;
        my_addr = my_addr->next;
    } while (my_addr);

    return NULL;
}


/* parse the <VirtualHost> addresses */
const char *ap_parse_vhost_addrs(apr_pool_t *p,
                                 const char *hostname,
                                 server_rec *s)
{
    server_addr_rec **addrs;
    const char *err;

    /* start the list of addresses */
    addrs = &s->addrs;
    while (hostname[0]) {
        err = get_addresses(p, ap_getword_conf(p, &hostname), &addrs, s->port);
        if (err) {
            *addrs = NULL;
            return err;
        }
    }
    /* terminate the list */
    *addrs = NULL;
    if (s->addrs) {
        if (s->addrs->host_port) {
            /* override the default port which is inherited from main_server */
            s->port = s->addrs->host_port;
        }
    }
    return NULL;
}


AP_DECLARE_NONSTD(const char *)ap_set_name_virtual_host(cmd_parms *cmd,
                                                        void *dummy,
                                                        const char *arg)
{
    static int warnonce = 0;
    if (++warnonce == 1) {
        ap_log_error(APLOG_MARK, APLOG_NOTICE|APLOG_STARTUP, APR_SUCCESS, NULL, APLOGNO(00548)
                     "NameVirtualHost has no effect and will be removed in the "
                     "next release %s:%d",
                     cmd->directive->filename,
                     cmd->directive->line_num);
    }

    return NULL;
}


/* hash table statistics, keep this in here for the beta period so
 * we can find out if the hash function is ok
 */
#ifdef IPHASH_STATISTICS
static int iphash_compare(const void *a, const void *b)
{
    return (*(const int *) b - *(const int *) a);
}


static void dump_iphash_statistics(server_rec *main_s)
{
    unsigned count[IPHASH_TABLE_SIZE];
    int i;
    ipaddr_chain *src;
    unsigned total;
    char buf[HUGE_STRING_LEN];
    char *p;

    total = 0;
    for (i = 0; i < IPHASH_TABLE_SIZE; ++i) {
        count[i] = 0;
        for (src = iphash_table[i]; src; src = src->next) {
            ++count[i];
            if (i < IPHASH_TABLE_SIZE) {
                /* don't count the slop buckets in the total */
                ++total;
            }
        }
    }
    qsort(count, IPHASH_TABLE_SIZE, sizeof(count[0]), iphash_compare);
    p = buf + apr_snprintf(buf, sizeof(buf),
                           APLOGNO(03235) "iphash: total hashed = %u, avg chain = %u, "
                           "chain lengths (count x len):",
                           total, total / IPHASH_TABLE_SIZE);
    total = 1;
    for (i = 1; i < IPHASH_TABLE_SIZE; ++i) {
        if (count[i - 1] != count[i]) {
            p += apr_snprintf(p, sizeof(buf) - (p - buf), " %ux%u",
                              total, count[i - 1]);
            total = 1;
        }
        else {
            ++total;
        }
    }
    p += apr_snprintf(p, sizeof(buf) - (p - buf), " %ux%u",
                      total, count[IPHASH_TABLE_SIZE - 1]);
    /* Intentional no APLOGNO */
    /* buf provides APLOGNO */
    ap_log_error(APLOG_MARK, APLOG_DEBUG, main_s, buf);
}
#endif


/* This hashing function is designed to get good distribution in the cases
 * where the server is handling entire "networks" of servers.  i.e. a
 * whack of /24s.  This is probably the most common configuration for
 * ISPs with large virtual servers.
 *
 * NOTE: This function is symmetric (i.e. collapses all 4 octets
 * into one), so machine byte order (big/little endianness) does not matter.
 *
 * Hash function provided by David Hankins.
 */
static APR_INLINE unsigned hash_inaddr(unsigned key)
{
    key ^= (key >> 16);
    return ((key >> 8) ^ key) % IPHASH_TABLE_SIZE;
}

static APR_INLINE unsigned hash_addr(struct apr_sockaddr_t *sa)
{
    unsigned key;

    /* The key is the last four bytes of the IP address.
     * For IPv4, this is the entire address, as always.
     * For IPv6, this is usually part of the MAC address.
     */
    key = *(unsigned *)((char *)sa->ipaddr_ptr + sa->ipaddr_len - 4);
    return hash_inaddr(key);
}

static ipaddr_chain *new_ipaddr_chain(apr_pool_t *p,
                                      server_rec *s, server_addr_rec *sar)
{
    ipaddr_chain *new;

    new = apr_palloc(p, sizeof(*new));
    new->names = NULL;
    new->initialnames = NULL;
    new->server = s;
    new->sar = sar;
    new->next = NULL;
    return new;
}


static name_chain *new_name_chain(apr_pool_t *p,
                                  server_rec *s, server_addr_rec *sar)
{
    name_chain *new;

    new = apr_palloc(p, sizeof(*new));
    new->server = s;
    new->sar = sar;
    new->next = NULL;
    return new;
}


static APR_INLINE ipaddr_chain *find_ipaddr(apr_sockaddr_t *sa)
{
    unsigned bucket;
    ipaddr_chain *trav = NULL;
    ipaddr_chain *wild_match = NULL;

    /* scan the hash table for an exact match first */
    bucket = hash_addr(sa);
    for (trav = iphash_table[bucket]; trav; trav = trav->next) {
        server_addr_rec *sar = trav->sar;
        apr_sockaddr_t *cur = sar->host_addr;

        if (cur->port == sa->port) {
            if (apr_sockaddr_equal(cur, sa)) {
                return trav;
            }
        }
        if (wild_match == NULL && (cur->port == 0 || sa->port == 0)) {
            if (apr_sockaddr_equal(cur, sa)) {
                /* don't break, continue looking for an exact match */
                wild_match = trav;
            }
        }
    }
    return wild_match;
}

static ipaddr_chain *find_default_server(apr_port_t port)
{
    server_addr_rec *sar;
    ipaddr_chain *trav = NULL;
    ipaddr_chain *wild_match = NULL;

    for (trav = default_list; trav; trav = trav->next) {
        sar = trav->sar;
        if (sar->host_port == port) {
            /* match! */
            return trav;
        }
        if (wild_match == NULL && sar->host_port == 0) {
            /* don't break, continue looking for an exact match */
            wild_match = trav;
        }
    }
    return wild_match;
}

#if APR_HAVE_IPV6
#define IS_IN6_ANYADDR(ad) ((ad)->family == APR_INET6                   \
                            && IN6_IS_ADDR_UNSPECIFIED(&(ad)->sa.sin6.sin6_addr))
#else
#define IS_IN6_ANYADDR(ad) (0)
#endif

static void dump_a_vhost(apr_file_t *f, ipaddr_chain *ic)
{
    name_chain *nc;
    int len;
    char buf[MAX_STRING_LEN];
    apr_sockaddr_t *ha = ic->sar->host_addr;

    if ((ha->family == APR_INET && ha->sa.sin.sin_addr.s_addr == INADDR_ANY)
        || IS_IN6_ANYADDR(ha)) {
        len = apr_snprintf(buf, sizeof(buf), "*:%u",
                           ic->sar->host_port);
    }
    else {
        len = apr_snprintf(buf, sizeof(buf), "%pI", ha);
    }
    if (ic->sar->host_port == 0) {
        buf[len-1] = '*';
    }
    if (ic->names == NULL) {
        apr_file_printf(f, "%-22s %s (%s:%u)\n", buf,
                        ic->server->server_hostname,
                        ic->server->defn_name, ic->server->defn_line_number);
        return;
    }
    apr_file_printf(f, "%-22s is a NameVirtualHost\n"
                    "%8s default server %s (%s:%u)\n",
                    buf, "", ic->server->server_hostname,
                    ic->server->defn_name, ic->server->defn_line_number);
    for (nc = ic->names; nc; nc = nc->next) {
        if (nc->sar->host_port) {
            apr_file_printf(f, "%8s port %u ", "", nc->sar->host_port);
        }
        else {
            apr_file_printf(f, "%8s port * ", "");
        }
        apr_file_printf(f, "namevhost %s (%s:%u)\n",
                        nc->server->server_hostname,
                        nc->server->defn_name, nc->server->defn_line_number);
        if (nc->server->names) {
            apr_array_header_t *names = nc->server->names;
            char **name = (char **)names->elts;
            int i;
            for (i = 0; i < names->nelts; ++i) {
                if (name[i]) {
                    apr_file_printf(f, "%16s alias %s\n", "", name[i]);
                }
            }
        }
        if (nc->server->wild_names) {
            apr_array_header_t *names = nc->server->wild_names;
            char **name = (char **)names->elts;
            int i;
            for (i = 0; i < names->nelts; ++i) {
                if (name[i]) {
                    apr_file_printf(f, "%16s wild alias %s\n", "", name[i]);
                }
            }
        }
    }
}

static void dump_vhost_config(apr_file_t *f)
{
    ipaddr_chain *ic;
    int i;

    apr_file_printf(f, "VirtualHost configuration:\n");

    /* non-wildcard servers */
    for (i = 0; i < IPHASH_TABLE_SIZE;/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @file  vhost.c
 * @brief functions pertaining to virtual host addresses
 *        (configuration and run-time)
 */

#include "apr.h"
#include "apr_strings.h"
#include "apr_lib.h"
#include "apr_version.h"

#define APR_WANT_STRFUNC
#include "apr_want.h"

#include "ap_config.h"
#include "httpd.h"
#include "http_config.h"
#include "http_log.h"
#include "http_vhost.h"
#include "http_protocol.h"
#include "http_core.h"
#include "http_main.h"

#if APR_HAVE_ARPA_INET_H
#include <arpa/inet.h>
#endif

/* we know core's module_index is 0 */
#undef APLOG_MODULE_INDEX
#define APLOG_MODULE_INDEX AP_CORE_MODULE_INDEX

/*
 * After all the definitions there's an explanation of how it's all put
 * together.
 */

/* meta-list of name-vhosts.  Each server_rec can be in possibly multiple
 * lists of name-vhosts.
 */
typedef struct name_chain name_chain;
struct name_chain {
    name_chain *next;
    server_addr_rec *sar;       /* the record causing it to be in
                                 * this chain (needed for port comparisons) */
    server_rec *server;         /* the server to use on a match */
};

/* meta-list of ip addresses.  Each server_rec can be in possibly multiple
 * hash chains since it can have multiple ips.
 */
typedef struct ipaddr_chain ipaddr_chain;
struct ipaddr_chain {
    ipaddr_chain *next;
    server_addr_rec *sar;       /* the record causing it to be in
                                 * this chain (need for both ip addr and port
                                 * comparisons) */
    server_rec *server;         /* the server to use if this matches */
    name_chain *names;          /* if non-NULL then a list of name-vhosts
                                 * sharing this address */
    name_chain *initialnames;   /* no runtime use, temporary storage of first
                                 * NVH'es names */
};

/* This defines the size of the hash table used for hashing ip addresses
 * of virtual hosts.  It must be a power of two.
 */
#ifndef IPHASH_TABLE_SIZE
#define IPHASH_TABLE_SIZE 256
#endif

/* A (n) bucket hash table, each entry has a pointer to a server rec and
 * a pointer to the other entries in that bucket.  Each individual address,
 * even for virtualhosts with multiple addresses, has an entry in this hash
 * table.  There are extra buckets for _default_, and name-vhost entries.
 *
 * Note that after config time this is constant, so it is thread-safe.
 */
static ipaddr_chain *iphash_table[IPHASH_TABLE_SIZE];

/* dump out statistics about the hash function */
/* #define IPHASH_STATISTICS */

/* list of the _default_ servers */
static ipaddr_chain *default_list;

/* whether a config error was seen */
static int config_error = 0;

/* config check function */
static int vhost_check_config(apr_pool_t *p, apr_pool_t *plog,
                              apr_pool_t *ptemp, server_rec *s);

/*
 * How it's used:
 *
 * The ip address determines which chain in iphash_table is interesting, then
 * a comparison is done down that chain to find the first ipaddr_chain whose
 * sar matches the address:port pair.
 *
 * If that ipaddr_chain has names == NULL then you're done, it's an ip-vhost.
 *
 * Otherwise it's a name-vhost list, and the default is the server in the
 * ipaddr_chain record.  We tuck away the ipaddr_chain record in the
 * conn_rec field vhost_lookup_data.  Later on after the headers we get a
 * second chance, and we use the name_chain to figure out what name-vhost
 * matches the headers.
 *
 * If there was no ip address match in the iphash_table then do a lookup
 * in the default_list.
 *
 * How it's put together ... well you should be able to figure that out
 * from how it's used.  Or something like that.
 */


/* called at the beginning of the config */
AP_DECLARE(void) ap_init_vhost_config(apr_pool_t *p)
{
    memset(iphash_table, 0, sizeof(iphash_table));
    default_list = NULL;
    ap_hook_check_config(vhost_check_config, NULL, NULL, APR_HOOK_MIDDLE);
}


/*
 * Parses a host of the form <address>[:port]
 * paddr is used to create a list in the order of input
 * **paddr is the ->next pointer of the last entry (or s->addrs)
 * *paddr is the variable used to keep track of **paddr between calls
 * port is the default port to assume
 */
static const char *get_addresses(apr_pool_t *p, const char *w_,
                                 server_addr_rec ***paddr,
                                 apr_port_t default_port)
{
    apr_sockaddr_t *my_addr;
    server_addr_rec *sar;
    char *w, *host, *scope_id;
    int wild_port;
    apr_size_t wlen;
    apr_port_t port;
    apr_status_t rv;

    if (*w_ == '\0')
        return NULL;

    wlen = strlen(w_);                   /* wlen must be > 0 at this point */
    w = apr_pstrmemdup(p, w_, wlen);
    /* apr_parse_addr_port() doesn't understand ":*" so handle that first. */
    wild_port = 0;
    if (w[wlen - 1] == '*') {
        if (wlen < 2) {
            wild_port = 1;
        }
        else if (w[wlen - 2] == ':') {
            w[wlen - 2] = '\0';
            wild_port = 1;
        }
    }
    rv = apr_parse_addr_port(&host, &scope_id, &port, w, p);
    /* If the string is "80", apr_parse_addr_port() will be happy and set
     * host to NULL and port to 80, so watch out for that.
     */
    if (rv != APR_SUCCESS) {
        return "The address or port is invalid";
    }
    if (!host) {
        return "Missing address for VirtualHost";
    }
#if !APR_VERSION_AT_LEAST(1,7,0)
    if (scope_id) {
        return apr_pstrcat(p,
                           "Scope ID in address '", w,
                           "' not supported with APR " APR_VERSION_STRING,
                           NULL);
    }
#endif
    if (!port && !wild_port) {
        port = default_port;
    }

    if (strcmp(host, "*") == 0 || strcasecmp(host, "_default_") == 0) {
        rv = apr_sockaddr_info_get(&my_addr, NULL, APR_UNSPEC, port, 0, p);
        if (rv) {
            return "Could not determine a wildcard address ('0.0.0.0') -- "
                "check resolver configuration.";
        }
    }
    else {
        rv = apr_sockaddr_info_get(&my_addr, host, APR_UNSPEC, port, 0, p);
        if (rv != APR_SUCCESS) {
            ap_log_error(APLOG_MARK, APLOG_ERR, rv, NULL, APLOGNO(00547)
                "Could not resolve host name %s -- ignoring!", host);
            return NULL;
        }
#if APR_VERSION_AT_LEAST(1,7,0)
        if (scope_id) {
            rv = apr_sockaddr_zone_set(my_addr, scope_id);
            if (rv) {
                ap_log_error(APLOG_MARK, APLOG_ERR, rv, NULL, APLOGNO(10103)
                             "Could not set scope ID %s for %pI -- ignoring!",
                             scope_id, my_addr);
                return NULL;
            }
        }
#endif
    }

    /* Remember all addresses for the host */

    do {
        sar = apr_pcalloc(p, sizeof(server_addr_rec));
        **paddr = sar;
        *paddr = &sar->next;
        sar->host_addr = my_addr;
        sar->host_port = port;
        sar->virthost = host;
        my_addr = my_addr->next;
    } while (my_addr);

    return NULL;
}


/* parse the <VirtualHost> addresses */
const char *ap_parse_vhost_addrs(apr_pool_t *p,
                                 const char *hostname,
                                 server_rec *s)
{
    server_addr_rec **addrs;
    const char *err;

    /* start the list of addresses */
    addrs = &s->addrs;
/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @file  vhost.c
 * @brief functions pertaining to virtual host addresses
 *        (configuration and run-time)
 */

#include "apr.h"
#include "apr_strings.h"
#include "apr_lib.h"
#include "apr_version.h"

#define APR_WANT_STRFUNC
#include "apr_want.h"

#include "ap_config.h"
#include "httpd.h"
#include "http_config.h"
#include "http_log.h"
#include "http_vhost.h"
#include "http_protocol.h"
#include "http_core.h"
#include "http_main.h"

#if APR_HAVE_ARPA_INET_H
#include <arpa/inet.h>
#endif

/* we know core's module_index is 0 */
#undef APLOG_MODULE_INDEX
#define APLOG_MODULE_INDEX AP_CORE_MODULE_INDEX

/*
 * After all the definitions there's an explanation of how it's all put
 * together.
 */

/* meta-list of name-vhosts.  Each server_rec can be in possibly multiple
 * lists of name-vhosts.
 */
typedef struct name_chain name_chain;
struct name_chain {
    name_chain *next;
    server_addr_rec *sar;       /* the record causing it to be in
                                 * this chain (needed for port comparisons) */
    server_rec *server;         /* the server to use on a match */
};

/* meta-list of ip addresses.  Each server_rec can be in possibly multiple
 * hash chains since it can have multiple ips.
 */
typedef struct ipaddr_chain ipaddr_chain;
struct ipaddr_chain {
    ipaddr_chain *next;
    server_addr_rec *sar;       /* the record causing it to be in
                                 * this chain (need for both ip addr and port
                                 * comparisons) */
    server_rec *server;         /* the server to use if this matches */
    name_chain *names;          /* if non-NULL then a list of name-vhosts
                                 * sharing this address */
    name_chain *initialnames;   /* no runtime use, temporary storage of first
                                 * NVH'es names */
};

/* This defines the size of the hash table used for hashing ip addresses
 * of virtual hosts.  It must be a power of two.
 */
#ifndef IPHASH_TABLE_SIZE
#define IPHASH_TABLE_SIZE 256
#endif

/* A (n) bucket hash table, each entry has a pointer to a server rec and
 * a pointer to the other entries in that bucket.  Each individual address,
 * even for virtualhosts with multiple addresses, has an entry in this hash
 * table.  There are extra buckets for _default_, and name-vhost entries.
 *
 * Note that after config time this is constant, so it is thread-safe.
 */
static ipaddr_chain *iphash_table[IPHASH_TABLE_SIZE];

/* dump out statistics about the hash function */
/* #define IPHASH_STATISTICS */

/* list of the _default_ servers */
static ipaddr_chain *default_list;

/* whether a config error was seen */
static int config_error = 0;

/* config check function */
static int vhost_check_config(apr_pool_t *p, apr_pool_t *plog,
                              apr_pool_t *ptemp, server_rec *s);

/*
 * How it's used:
 *
 * The ip address determines which chain in iphash_table is interesting, then
 * a comparison is done down that chain to find the first ipaddr_chain whose
 * sar matches the address:port pair.
 *
 * If that ipaddr_chain has names == NULL then you're done, it's an ip-vhost.
 *
 * Otherwise it's a name-vhost list, and the default is the server in the
 * ipaddr_chain record.  We tuck away the ipaddr_chain record in the
 * conn_rec field vhost_lookup_data.  Later on after the headers we get a
 * second chance, and we use the name_chain to figure out what name-vhost
 * matches the headers.
 *
 * If there was no ip address match in the iphash_table then do a lookup
 * in the default_list.
 *
 * How it's put together ... well you should be able to figure that out
 * from how it's used.  Or something like that.
 */


/* called at the beginning of the config */
AP_DECLARE(void) ap_init_vhost_config(apr_pool_t *p)
{
    memset(iphash_table, 0, sizeof(iphash_table));
    default_list = NULL;
    ap_hook_check_config(vhost_check_config, NULL, NULL, APR_HOOK_MIDDLE);
}


/*
 * Parses a host of the form <address>[:port]
 * paddr is used to create a list in the order of input
 * **paddr is the ->next pointer of the last entry (or s->addrs)
 * *paddr is the variable used to keep track of **paddr between calls
 * port is the default port to assume
 */
static const char *get_addresses(apr_pool_t *p, const char *w_,
                                 server_addr_rec ***paddr,
                                 apr_port_t default_port)
{
    apr_sockaddr_t *my_addr;
    server_addr_rec *sar;
    char *w, *host, *scope_id;
    int wild_port;
    apr_size_t wlen;
    apr_port_t port;
    apr_status_t rv;

    if (*w_ == '\0')
        return NULL;

    wlen = strlen(w_);                   /* wlen must be > 0 at this point */
    w = apr_pstrmemdup(p, w_, wlen);
    /* apr_parse_addr_port() doesn't understand ":*" so handle that first. */
    wild_port = 0;
    if (w[wlen - 1] == '*') {
        if (wlen < 2) {
            wild_port = 1;
        }
        else if (w[wlen - 2] == ':') {
            w[wlen - 2] = '\0';
            wild_port = 1;
        }
    }
    rv = apr_parse_addr_port(&host, &scope_id, &port, w, p);
    /* If the string is "80", apr_parse_addr_port() will be happy and set
     * host to NULL and port to 80, so watch out for that.
     */
    if (rv != APR_SUCCESS) {
        return "The address or port is invalid";
    }
    if (!host) {
        return "Missing address for VirtualHost";
    }
#if !APR_VERSION_AT_LEAST(1,7,0)
    if (scope_id) {
        return apr_pstrcat(p,
                           "Scope ID in address '", w,
                           "' not supported with APR " APR_VERSION_STRING,
                           NULL);
    }
#endif
    if (!port && !wild_port) {
        port = default_port;
    }

    if (strcmp(host, "*") == 0 || strcasecmp(host, "_default_") == 0) {
        rv = apr_sockaddr_info_get(&my_addr, NULL, APR_UNSPEC, port, 0, p);
        if (rv) {
            return "Could not determine a wildcard address ('0.0.0.0') -- "
                "check resolver configuration.";
        }
    }
    else {
        rv = apr_sockaddr_info_get(&my_addr, host, APR_UNSPEC, port, 0, p);
        if (rv != APR_SUCCESS) {
            ap_log_error(APLOG_MARK, APLOG_ERR, rv, NULL, APLOGNO(00547)
                "Could not resolve host name %s -- ignoring!", host);
            return NULL;
        }
#if APR_VERSION_AT_LEAST(1,7,0)
        if (scope_id) {
            rv = apr_sockaddr_zone_set(my_addr, scope_id);
            if (rv) {
                ap_log_error(APLOG_MARK, APLOG_ERR, rv, NULL, APLOGNO(10103)
                             "Could not set scope ID %s for %pI -- ignoring!",
                             scope_id, my_addr);
                return NULL;
            }
        }
#endif
    }

    /* Remember all addresses for the host */

    do {
        sar = apr_pcalloc(p, sizeof(server_addr_rec));
        **paddr = sar;
        *paddr = &sar->next;
        sar->host_addr = my_addr;
        sar->host_port = port;
        sar->virthost = host;
        my_addr = my_addr->next;
    } while (my_addr);

    return NULL;
}


/* parse the <VirtualHost> addresses */
const char *ap_parse_vhost_addrs(apr_pool_t *p,
                                 const char *hostname,
                                 server_rec *s)
{
    server_addr_rec **addrs;
    const char *err;

    /* start the list of addresses */
    addrs = &s->addrs;
    while (hostname[0]) {
        err = get_addresses(p, ap_getword_conf(p, &hostname), &addrs, s->port);
        if (err) {
            *addrs = NULL;
            return err;
        }
    }
    /* terminate the list */
    *addrs = NULL;
    if (s->addrs) {
        if (s->addrs->host_port) {
            /* override the default port which is inherited from main_server */
            s->port = s->addrs->host_port;
        }
    }
    return NULL;
}


AP_DECLARE_NONSTD(const char *)ap_set_name_virtual_host(cmd_parms *cmd,
                                                        void *dummy,
                                                        const char *arg)
{
    static int warnonce = 0;
    if (++warnonce == 1) {
        ap_log_error(APLOG_MARK, APLOG_NOTICE|APLOG_STARTUP, APR_SUCCESS, NULL, APLOGNO(00548)
                     "NameVirtualHost has no effect and will be removed in the "
                     "next release %s:%d",
                     cmd->directive->filename,
                     cmd->directive->line_num);
    }

    return NULL;
}


/* hash table statistics, keep this in here for the beta period so
 * we can find out if the hash function is ok
 */
#ifdef IPHASH_STATISTICS
static int iphash_compare(const void *a, const void *b)
{
    return (*(const int *) b - *(const int *) a);
}


static void dump_iphash_statistics(server_rec *main_s)
{
    unsigned count[IPHASH_TABLE_SIZE];
    int i;
    ipaddr_chain *src;
    unsigned total;
    char buf[HUGE_STRING_LEN];
    char *p;

    total = 0;
    for (i = 0; i < IPHASH_TABLE_SIZE; ++i) {
        count[i] = 0;
        for (src = iphash_table[i]; src; src = src->next) {
            ++count[i];
            if (i < IPHASH_TABLE_SIZE) {
                /* don't count the slop buckets in the total */
                ++total;
            }
        }
    }
    qsort(count, IPHASH_TABLE_SIZE, sizeof(count[0]), iphash_compare);
    p = buf + apr_snprintf(buf, sizeof(buf),
                           APLOGNO(03235) "iphash: total hashed = %u, avg chain = %u, "
                           "chain lengths (count x len):",
                           total, total / IPHASH_TABLE_SIZE);
    total = 1;
    for (i = 1; i < IPHASH_TABLE_SIZE; ++i) {
        if (count[i - 1] != count[i]) {
            p += apr_snprintf(p, sizeof(buf) - (p - buf), " %ux%u",
                              total, count[i - 1]);
            total = 1;
        }
        else {
            ++total;
        }
    }
    p += apr_snprintf(p, sizeof(buf) - (p - buf), " %ux%u",
                      total, count[IPHASH_TABLE_SIZE - 1]);
    /* Intentional no APLOGNO */
    /* buf provides APLOGNO */
    ap_log_error(APLOG_MARK, APLOG_DEBUG, main_s, buf);
}
#endif


/* This hashing function is designed to get good distribution in the cases
 * where the server is handling entire "networks" of servers.  i.e. a
 * whack of /24s.  This is probably the most common configuration for
 * ISPs with large virtual servers.
 *
 * NOTE: This function is symmetric (i.e. collapses all 4 octets
 * into one), so machine byte order (big/little endianness) does not matter.
 *
 * Hash function provided by David Hankins.
 */
static APR_INLINE unsigned hash_inaddr(unsigned key)
{
    key ^= (key >> 16);
    return ((key >> 8) ^ key) % IPHASH_TABLE_SIZE;
}

static APR_INLINE unsigned hash_addr(struct apr_sockaddr_t *sa)
{
    unsigned key;

    /* The key is the last four bytes of the IP address.
     * For IPv4, this is the entire address, as always.
     * For IPv6, this is usually part of the MAC address.
     */
    key = *(unsigned *)((char *)sa->ipaddr_ptr + sa->ipaddr_len - 4);
    return hash_inaddr(key);
}

static ipaddr_chain *new_ipaddr_chain(apr_pool_t *p,
                                      server_rec *s, server_addr_rec *sar)
{
    ipaddr_chain *new;

    new = apr_palloc(p, sizeof(*new));
    new->names = NULL;
    new->initialnames = NULL;
    new->server = s;
    new->sar = sar;
    new->next = NULL;
    return new;
}


static name_chain *new_name_chain(apr_pool_t *p,
                                  server_rec *s, server_addr_rec *sar)
{
    name_chain *new;

    new = apr_palloc(p, sizeof(*new));
    new->server = s;
    new->sar = sar;
    new->next = NULL;
    return new;
}


static APR_INLINE ipaddr_chain *find_ipaddr(apr_sockaddr_t *sa)
{
    unsigned bucket;
    ipaddr_chain *trav = NULL;
    ipaddr_chain *wild_match = NULL;

    /* scan the hash table for an exact match first */
    bucket = hash_addr(sa);
    for (trav = iphash_table[bucket]; trav; trav = trav->next) {
        server_addr_rec *sar = trav->sar;
        apr_sockaddr_t *cur = sar->host_addr;

        if (cur->port == sa->port) {
            if (apr_sockaddr_equal(cur, sa)) {
                return trav;
            }
        }
        if (wild_match == NULL && (cur->port == 0 || sa->port == 0)) {
            if (apr_sockaddr_equal(cur, sa)) {
                /* don't break, continue looking for an exact match */
                wild_match = trav;
            }
        }
    }
    return wild_match;
}

static ipaddr_chain *find_default_server(apr_port_t port)
{
    server_addr_rec *sar;
    ipaddr_chain *trav = NULL;
    ipaddr_chain *wild_match = NULL;

    for (trav = default_list; trav; trav = trav->next) {
        sar = trav->sar;
        if (sar->host_port == port) {
            /* match! */
            return trav;
        }
        if (wild_match == NULL && sar->host_port == 0) {
            /* don't break, continue looking for an exact match */
            wild_match = trav;
        }
    }
    return wild_match;
}

#if APR_HAVE_IPV6
#define IS_IN6_ANYADDR(ad) ((ad)->family == APR_INET6                   \
                            && IN6_IS_ADDR_UNSPECIFIED(&(ad)->sa.sin6.sin6_addr))
#else
#define IS_IN6_ANYADDR(ad) (0)
#endif

static void dump_a_vhost(apr_file_t *f, ipaddr_chain *ic)
{
    name_chain *nc;
    int len;
    char buf[MAX_STRING_LEN];
    apr_sockaddr_t *ha = ic->sar->host_addr;

    if ((ha->family == APR_INET && ha->sa.sin.sin_addr.s_addr == INADDR_ANY)
        || IS_IN6_ANYADDR(ha)) {
        len = apr_snprintf(buf, sizeof(buf), "*:%u",
                           ic->sar->host_port);
    }
    else {
        len = apr_snprintf(buf, sizeof(buf), "%pI", ha);
    }
    if (ic->sar->host_port == 0) {
        buf[len-1] = '*';
    }
    if (ic->names == NULL) {
        apr_file_printf(f, "%-22s %s (%s:%u)\n", buf,
                        ic->server->server_hostname,
                        ic->server->defn_name, ic->server->defn_line_number);
        return;
    }
    apr_file_printf(f, "%-22s is a NameVirtualHost\n"
                    "%8s default server %s (%s:%u)\n",
                    buf, "", ic->server->server_hostname,
                    ic->server->defn_name, ic->server->defn_line_number);
    for (nc = ic->names; nc; nc = nc->next) {
        if (nc->sar->host_port) {
            apr_file_printf(f, "%8s port %u ", "", nc->sar->host_port);
        }
        else {
            apr_file_printf(f, "%8s port * ", "");
        }
        apr_file_printf(f, "namevhost %s (%s:%u)\n",
                        nc->server->server_hostname,
                        nc->server->defn_name, nc->server->defn_line_number);
        if (nc->server->names) {
            apr_array_header_t *names = nc->server->names;
            char **name = (char **)names->elts;
            int i;
            for (i = 0; i < names->nelts; ++i) {
                if (name[i]) {
                    apr_file_printf(f, "%16s alias %s\n", "", name[i]);
                }
            }
        }
        if (nc->server->wild_names) {
            apr_array_header_t *names = nc->server->wild_names;
            char **name = (char **)names->elts;
            int i;
            for (i = 0; i < names->nelts; ++i) {
                if (name[i]) {
                    apr_file_printf(f, "%16s wild alias %s\n", "", name[i]);
                }
            }
        }
    }
}

static void dump_vhost_config(apr_file_t *f)
{
    ipaddr_chain *ic;
    int i;

    apr_file_printf(f, "VirtualHost configuration:\n");

    /* non-wildcard servers */
    for (i = 0; i < IPHASH_TABLE_SIZE; ++i) {
        for (ic = iphash_table[i]; ic; ic = ic->next) {
            dump_a_vhost(f, ic);
        }
    }

    /* wildcard servers */
    for (ic = default_list; ic; ic = ic->next) {
        dump_a_vhost(f, ic);
    }
}


/*
 * When a second or later virtual host maps to the same IP chain,
 * add the relevant server names to the chain.  Special care is taken
 * to avoid adding ic->names un