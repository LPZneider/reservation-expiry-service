FROM eclipse-temurin:25-jdk AS builder

RUN groupadd -r appgroup && useradd -r -g appgroup -s /bin/false appuser

WORKDIR /builder

ARG JAR_FILE=applications/app-service/build/libs/*.jar
COPY ${JAR_FILE} application.jar
RUN chown appuser:appgroup application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted
RUN chown -R appuser:appgroup extracted

FROM eclipse-temurin:25-jdk

RUN groupadd -r appgroup && useradd -r -g appgroup -s /bin/false appuser

WORKDIR /application

RUN rm -rf /var/lib/apt/lists/* && \
    apt-get update && \
    apt-get install -y curl && \
    apt-get clean && \
    usermod -a -G appgroup appuser

COPY --from=builder --chown=appuser:appgroup /builder/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /builder/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /builder/extracted/application/ ./

RUN java -XX:ArchiveClassesAtExit=application.jsa -Dspring.context.exit=onRefresh -jar application.jar

ENV TZ=America/Bogota
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=65 -Djava.security.egd=file:/dev/./urandom -XX:SharedArchiveFile=application.jsa"

USER appuser
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -jar application.jar" ]
