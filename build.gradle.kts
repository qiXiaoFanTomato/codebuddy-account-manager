import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.codebuddy.account"
version = "0.45.1"

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
        // 用「最低支持版本」的 SDK 编译：这样误用新版平台 API 会直接编译失败，
        // 而不是等到用户在旧 IDE 上运行时报 NoSuchMethodError。
        create(IntelliJPlatformType.IntellijIdeaCommunity, "2022.3")
        // 本机已装新版 IDE 时可临时切换为 local 验证新版编译：
        // local("D:/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1")
    }
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    // Gradle 9 不再隐式提供 JUnit Platform Launcher，未显式声明时 test 任务起不来
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 字节码降到 JDK 8：JBR 8 / 11 / 17 / 21 的 IDE 运行时都能加载
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

tasks {
    // 随插件 jar 分发第三方开源声明，满足 Apache-2.0 与 CC BY-NC-SA 4.0 的署名/附本要求
    processResources {
        from(rootProject.file("THIRD-PARTY-NOTICES.md"))
    }
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
        options.encoding = "UTF-8"
    }
    test {
        useJUnitPlatform()
    }
    patchPluginXml {
        // 223 = 2022.3（IntelliJ Platform Gradle Plugin 2.x 支持的最低平台版本）
        sinceBuild.set("223")
        // 不写上限：新版本 IDE 上即使平台内部实现变化也能照常安装，由运行时兜底逻辑降级
        untilBuild.set(provider { null })
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
    // 用「最低支持版本」实测市场那套 IntelliJ Plugin Verifier：
    // 市场按 2022.3.3（223.8836.41）判过一次 Critical，本地要复现就验同一 build。
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2022.3.3")
        }
    }
}
