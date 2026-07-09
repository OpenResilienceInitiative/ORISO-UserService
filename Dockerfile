FROM eclipse-temurin:21-jre
VOLUME ["/tmp","/log"]
EXPOSE 8082
ARG JAR_FILE
ENV JAVA_UPPER_VERSION=eclipse-temurin:21-jre
COPY ./target/UserService.jar app.jar
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-XX:MaxRAMPercentage=75","-jar","/app.jar"]
