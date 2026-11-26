buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
}
plugins {
    id("com.android.application") version "9.2.1"
}

android {
    namespace = "local.wetype.couiglass"
    compileSdk = 37

    defaultConfig {
        applicationId = "local.wetype.couiglass"
        minSdk = 31
        targetSdk = 37
        versionCode = 31
        versionName = "0.4.6"
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.merges += "META-INF/xposed/*"
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.itoseo:coui:0.0.1-alpha12")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
