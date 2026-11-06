# Build stage: compile the single-file app with the full JDK
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY OrderApp.java .
RUN javac -d /out OrderApp.java

# Runtime stage: JRE only, non-root user
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre-alpine
RUN adduser -D -u 10001 onward
WORKDIR /app
COPY --from=build /out/ /app/
USER onward
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/app", "OrderApp"]
