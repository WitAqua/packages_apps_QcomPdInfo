/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * A second way to build the same sources. Soong is the primary one - the ROM
 * variant wants the platform signature and the system shared user id, which
 * only a build inside an android tree can give it - but a tree is not
 * something CI has, so the sideloaded variant is built here instead.
 *
 * Only QcomPdInfoRoot comes out of this. It is the same code either way: which
 * kernel interface is read, and whether the files are opened directly or
 * through a root shell, are both decided at runtime.
 */

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    /*
     * The resources and the generated R belong to the library's package, not
     * to the application id - the Kotlin in core/ and ui/ refers to
     * org.witaqua.qcom.pd_info.R and has to keep finding it.
     */
    namespace = "org.witaqua.qcom.pd_info"
    compileSdk = 35

    defaultConfig {
        /*
         * Distinct from the ROM build's, so the two can sit on one handset:
         * somebody running a WitAqua ROM may still want to install this over
         * it to compare.
         */
        applicationId = "org.witaqua.qcom.pd_info.root"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets {
        named("main") {
            manifest.srcFile("gradle/AndroidManifest.xml")
            kotlin.setSrcDirs(listOf("core/src", "ui/src"))
            res.setSrcDirs(listOf("core/res", "ui/res"))
        }
    }

    signingConfigs {
        create("release") {
            /*
             * Passed in by CI out of the private key repository. A build
             * without them still runs - unsigned, which is enough to know the
             * sources compile - and only a release has to be signed.
             */
            val store = System.getenv("STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard.flags",
            )
            signingConfig = signingConfigs.getByName("release")
                .takeIf { System.getenv("STORE_FILE") != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /*
     * The apk goes into a module zip under a name the packer looks for, so
     * take the variant suffix off rather than renaming it afterwards.
     */
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "QcomPdInfoRoot.apk"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference:1.2.1")
}
