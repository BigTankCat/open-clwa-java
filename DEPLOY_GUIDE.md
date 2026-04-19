# OpenClaw Java 部署指南

## 快速部署步骤

### 1. 确保Java环境
```bash
export JAVA_HOME="/Users/a/work/soft/jdk-21.0.10.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -version  # 确认Java 21
```

### 2. 编译项目
```bash
cd /Users/a/work/openclaw/openclaw-java
./mvnw clean compile -DskipTests
```

### 3. 启动服务
```bash
cd openclaw-gateway
../mvnw spring-boot:run
```

### 4. 访问服务
- WebSocket: ws://localhost:8080/ws
- HTTP Health: http://localhost:8080/health

## 常见问题

### Maven Wrapper不存在
```bash
# 安装Maven
brew install maven
# 或使用wrapper生成
mvn wrapper:wrapper
```

### 端口被占用
```bash
# 修改端口
export OPENCLAW_GATEWAY_PORT=18080
```

### 编译失败
1. 检查Java版本是否为21
2. 清理Maven缓存: `rm -rf ~/.m2/repository`
3. 重新编译

## 生产环境部署

### 构建可执行JAR
```bash
./mvnw clean package -DskipTests
# 生成的jar在: openclaw-gateway/target/*.jar
```

### 运行JAR
```bash
java -jar openclaw-gateway-*.jar
```

### Docker部署
```dockerfile
FROM eclipse-temurin:21-jdk
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```
