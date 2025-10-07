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
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev10")
    kapt("com.github.Moriafly:spw-workshop-api:0.1.0-dev10")
    implementation("org.eclipse.jetty:jetty-server:11.0.15")
    implementation("org.eclipse.jetty:jetty-servlet:11.0.15")
    compileOnly("net.java.dev.jna:jna:5.10.0")
    compileOnly("net.java.dev.jna:jna-platform:5.10.0")
    implementation("org.json:json:20210307")
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.google.code.gson:gson:2.10.1")
    compileOnly ("net.jthink:jaudiotagger:3.0.1")
}
val pluginClass = "com.zmxl.plugin.SpwControlPlugin"
val pluginId = "SaltLyricPlugin"
val pluginVersion = "2.1.2"
val pluginProvider = "zmxl"
val PluginHasConfig = "true"
val PluginOpenSourceUrl = "https://github.com/zmxlsss666/SaltLyricPlugin"
val PluginDescription = "一个适用于 Salt Player For Windows 的桌面歌词插件，觉得好用请点个Star"

tasks.named<Jar>("jar") {
    manifest {
        attributes["Plugin-Class"] = pluginClass
        attributes["Plugin-Id"] = pluginId
        attributes["Plugin-Version"] = pluginVersion
        attributes["Plugin-Provider"] = pluginProvider
        attributes["Plugin-Has-Config"] = PluginHasConfig
        attributes["Plugin-Open-Source-Url"] = PluginOpenSourceUrl
        attributes["Plugin-Description"] = PluginDescription
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








