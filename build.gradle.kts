plugins {
    id("java-library")
    kotlin("jvm") version "2.1.0"
    kotlin("kapt") version "2.1.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
    // 启用KAPT对Kotlin 2.0+的支持（解决警告）
    sourceSets.main {
        kotlin.srcDir("build/generated/source/kaptKotlin/main")
    }
}


dependencies {
    // SPW API
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev08")
    kapt("com.github.Moriafly:spw-workshop-api:0.1.0-dev08")
    
    // 添加SaltAudioTag库（正确坐标）
    implementation("io.github.moriafly:salt-audiotag:0.1.0-dev12")
    
    // Jetty HTTP服务器
    implementation("org.eclipse.jetty:jetty-server:11.0.15")
    implementation("org.eclipse.jetty:jetty-servlet:11.0.15")
    
    // JNA
    implementation("net.java.dev.jna:jna:5.10.0")
    implementation("net.java.dev.jna:jna-platform:5.10.0")
    implementation("org.json:json:20210307")
    
    // Kotlin标准库
    implementation(kotlin("stdlib-jdk8"))
    
    // JSON序列化
    implementation("com.google.code.gson:gson:2.10.1")
}

val pluginClass = "com.zmxl.plugin.SpwControlPlugin"
val pluginId = "zmxl-spw-control"
val pluginVersion = "1.1.0"
val pluginProvider = "zmxl"

tasks.named<Jar>("jar") {
    manifest {
        attributes["Plugin-Class"] = pluginClass
        attributes["Plugin-Id"] = pluginId
        attributes["Plugin-Version"] = pluginVersion
        attributes["Plugin-Provider"] = pluginProvider
    }
}

tasks.register<Jar>("plugin") {
    archiveBaseName.set("plugin-$pluginId-$pluginVersion")
    into("classes") {
        with(tasks.named<Jar>("jar").get())
    }
    dependsOn(configurations.runtimeClasspath)
    into("lib") {
        from(configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") })
    }
    archiveExtension.set("zip")
}

// 解决KAPT对Kotlin 2.0+的支持问题
tasks.withType<org.jetbrains.kotlin.gradle.tasks.Kapt> {
    kotlinOptions {
        languageVersion = "2.1"
        apiVersion = "2.1"
    }
}

