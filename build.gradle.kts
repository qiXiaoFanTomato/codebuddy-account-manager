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
