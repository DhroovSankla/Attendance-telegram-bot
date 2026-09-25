FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY lib ./lib
COPY *.java ./

RUN javac -cp ".:lib/*" AttendanceFetcher.java TelegramBot.java

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY lib ./lib
COPY --from=builder /app/*.class ./

CMD ["java", "-cp", ".:lib/*", "TelegramBot"]
