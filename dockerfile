# Étape 1 : Utiliser une image de base légère avec OpenJDK
FROM openjdk:21-jdk-slim

# Étape 2 : Définir le répertoire de travail dans le conteneur
WORKDIR /app

# Étape 3 : Copier les fichiers nécessaires dans le conteneur
COPY MonProjet.jar /app/app.jar
COPY mysql-connector-j-9.1.0 /app/mysql-connector-java.jar

# Étape 4 : Spécifier la commande pour exécuter l'application
CMD ["java", "-cp", "/app/app.jar:/app/mysql-connector-java.jar", "Main"]



