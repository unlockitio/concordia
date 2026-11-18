# SPDX-License-Identifier: Apache-2.0
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY java/pom.xml ./pom.xml
COPY java/src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
FROM eclipse-temurin:21-jre
WORKDIR /app/java
COPY --from=build /build/target/classes/ /app/classes/
COPY --from=build /build/target/dependency/ /app/lib/
COPY impl/daml/ /app/impl/daml/
COPY demo/compose/agents/entrypoint.sh /app/entrypoint.sh
USER 65532:65532
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
