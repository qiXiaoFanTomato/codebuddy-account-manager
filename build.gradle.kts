plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.codebuddy.account"
version = "0.10.3"

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("D:/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1")
    }
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.encoding = "UTF-8"
    }
    test {
        useJUnitPlatform()
    }
    patchPluginXml {
        sinceBuild.set("262")
        untilBuild.set("262.*")
    }
    instrumentCode {
        enabled = false
    }
    instrumentTestCode {
        enabled = false
    }
    buildSearchableOptions {
        enabled = false
    }
}

/**
 * 发布到 JetBrains Marketplace 的授权配置。
 *
 * 未设置 PUBLISH_TOKEN 时不影响本地构建；需要命令行发布时再设置：
 *   $env:PUBLISH_TOKEN = "<Marketplace Tokens 页面生成的永久 token>"
 *   .\gradlew.bat publishPlugin
 *
 * Token 生成位置：plugins.jetbrains.com → 个人头像 → Tokens。
 */
intellijPlatform {
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
