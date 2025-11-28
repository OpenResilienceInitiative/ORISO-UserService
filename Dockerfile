FROM adoptopenjdk/openjdk11
VOLUME ["/tmp","/log"]
EXPOSE 8080
ARG JAR_FILE
ENV ENABLE_JDWP=false
COPY ./UserService.jar app.jar
ENTRYPOINT ["sh","-c","if [ \"$ENABLE_JDWP\" = \"true\" ]; then DEBUG_OPTS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005'; else DEBUG_OPTS=''; fi; exec java $DEBUG_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app.jar"]