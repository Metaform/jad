# -buster is required to have apt available
FROM eclipse-temurin:23_37-jre-alpine

# Optional JVM arguments, such as memory settings
ARG JVM_ARGS=""
ARG JAR

RUN apk --no-cache add curl

WORKDIR /app


COPY ${JAR} identity-hub.jar

EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=5s --retries=10 CMD curl --fail http://localhost:8080/api/check/health

# Use "exec" for graceful termination (SIGINT) to reach JVM.
# ARG can not be used in ENTRYPOINT so storing value in an ENV variable
ENV ENV_JVM_ARGS=$JVM_ARGS
# need the sh -c syntax so that the SECRETS variable gets expanded
# use the "exec" syntax so that SIGINT reaches the JVM -> graceful termination
CMD ["sh", "-c", "exec java -Djava.security.egd=file:/dev/urandom -jar identity-hub.jar --log-level=debug"]
