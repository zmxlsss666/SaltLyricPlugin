plugins {
    id("java-library")
    kotlin("jvm") version "1.9.22"
    kotlin("kapt") version "1.9.22"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // SPW API
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev08")
    kapt("com.github.Moriafly:spw-workshop-api:0.1.0-dev08")
    
    // Jetty HTTP服务器
    implementation("org.eclipse.jetty:jetty-server:11.0.15")
    implementation("org.eclipse.jetty:jetty-servlet:11.0.15")
    
    // 最新JNA坐标（已迁移到net.java.dev.jna）
    implementation("net.java.dev.jna:jna:5.10.0")
    implementation("net.java.dev.jna:jna-platform:5.10.0")
    implementation("org.json:json:20210307")
    // Kotlin标准库
    implementation(kotlin("stdlib-jdk8"))
    
    // JSON序列化
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Apache Tika for metadata extraction
    implementation("org.apache.tika:tika-core:2.9.1")
    implementation("org.apache.tika:tika-parsers:2.9.1")
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
