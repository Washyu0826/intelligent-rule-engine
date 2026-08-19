# ============================================================
# Rules MCP Server multi-stage Dockerfile
# ============================================================
# Stage 1: Build backend (Maven)
# Stage 2: Build frontend (Node/Vite)
# Stage 3: Runtime (JRE only)
# ============================================================

FROM maven:3.9-eclipse-temurin-21-alpine AS backend-build
WORKDIR /app

# Cache Maven dependencies.
COPY pom.xml .
RUN mvn dependency:go-offline -B 2>/dev/null || true

COPY src ./src
RUN mvn clean package -DskipTests -q

FROM node:20-alpine AS frontend-build
WORKDIR /app

COPY frontend-src/frontend/package.json frontend-src/frontend/package-lock.json ./
RUN npm ci

COPY frontend-src/frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="Group Rules Team"
LABEL description="Rules MCP Server"

WORKDIR /app

COPY --from=backend-build /app/target/rules-mcp-server-*.jar app.jar
COPY --from=frontend-build /app/dist ./static/

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-Xmx512m -Xms256m" \
    SPRING_PROFILES_ACTIVE="default" \
    SERVER_PORT=8080

EXPOSE 8080

# exec 讓 JVM 取代 sh 成為 PID 1，SIGTERM 才能直達 JVM 觸發 graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
