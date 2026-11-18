plugins {
    alias(libs.plugins.android.library)
}

// Vendored Termux terminal-emulator and terminal-view (Apache-2.0). See terminal/README.md. DESIGN §5.
android {
    // The vendored code imports com.termux.view.R.
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 35
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os") }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        // Upstream code; the app module lint covers the code that we write.
        checkReleaseBuilds = false
        disable += "ChromeOsAbiSupport"
    }
}
