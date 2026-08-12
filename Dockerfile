# syntax=docker/dockerfile:1
# Printer (fără reclame) — build APK + pagina de download.
#
# Un singur deliverable: APK-ul de debug, servit de nginx la /a.apk, cu o pagină
# de landing la / (printer.d.ocl.ro, host :18056).
#
#     DOCKER_BUILDKIT=1 docker build -t printer-no-adds:dev .
#
# Structura urmează mobile-ionic/Dockerfile din repo-ul ocl: stage greu cu Android
# SDK + Gradle, imagine finală nginx subțire.

# ---- Stage: debug-signed Android APK --------------------------------------
# cirruslabs/android-sdk:35 vine cu Android SDK 35 + JDK 17 și licențele acceptate
# (se potrivește cu compileSdk 35 / AGP 8.5.2). Wrapper-ul nu e commitat (lipsește
# gradle-wrapper.jar, e binar), deci aducem distribuția Gradle 8.9 — aceeași
# versiune ca în gradle/wrapper/gradle-wrapper.properties — și rulăm `gradle` direct.
# Cache mount pe GRADLE_USER_HOME ține dependențele între build-uri.
FROM ghcr.io/cirruslabs/android-sdk:35 AS apk

ARG GRADLE_VERSION=8.9
ENV GRADLE_USER_HOME=/root/.gradle
ENV PATH=/opt/gradle/gradle-${GRADLE_VERSION}/bin:${PATH}

RUN curl -fsSL -o /tmp/gradle.zip \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && mkdir -p /opt/gradle \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm /tmp/gradle.zip

WORKDIR /src
COPY settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY app ./app

RUN --mount=type=cache,target=/root/.gradle \
    gradle --no-daemon assembleDebug \
    && cp app/build/outputs/apk/debug/app-debug.apk /a.apk

# ---- Imaginea finală: nginx cu APK-ul + pagina de download ----------------
FROM nginx:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY web/index.html /usr/share/nginx/html/index.html
COPY --from=apk /a.apk /usr/share/nginx/html/a.apk
