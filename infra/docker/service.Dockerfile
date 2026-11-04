FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY services ./services
COPY db ./db
COPY contracts ./contracts
COPY 需求与技术文档 ./需求与技术文档
ARG SERVICE
RUN mvn -B -ntp -pl services/${SERVICE} -am package -DskipTests && cp services/${SERVICE}/target/*-service-*.jar /service.jar

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
WORKDIR /app
COPY --from=build --chown=10001:10001 /service.jar /app/service.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/service.jar"]
