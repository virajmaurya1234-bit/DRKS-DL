FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY . .
RUN javac -d out src/Server.java
ENV DATA_DIR=/data
VOLUME /data
EXPOSE 3000
CMD ["java","-cp","out","Server"]
