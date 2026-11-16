# NEXUS interface in server mode behind nginx, which forwards /api and /ws to the server.
FROM eclipse-temurin:25-jdk AS core
WORKDIR /src
COPY . .
# the Java core for the browser is part of the bundle (alias @nexus-core), even in server mode
RUN ./mvnw -B -q -pl platform/web -am package -DskipTests

FROM node:24 AS build
COPY --from=core /src /src
WORKDIR /src/FactoryTwin
RUN npm ci --ignore-scripts && npx vite build --mode server

FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/FactoryTwin/dist /usr/share/nginx/html
