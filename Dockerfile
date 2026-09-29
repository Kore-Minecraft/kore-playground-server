# Kore playground compile backend with a trained IR cache and JDK AOT cache, see README-KORE.md.
# Gradle runs on the host (docker-image-build.sh or CI): the context carries the boot jar, the <KOTLIN_VERSION>
# and <KOTLIN_VERSION>-js library folders, and ir-cache-seed/ (a previous build's IR cache, or empty).

FROM amazoncorretto:25-al2023 AS assemble

ARG KOTLIN_VERSION

RUN test -n "$KOTLIN_VERSION" \
	|| { echo "KOTLIN_VERSION build arg is not set, build through docker-image-build.sh" >&2; exit 1; }

WORKDIR /staging
COPY build/libs/kotlin-compiler-server-${KOTLIN_VERSION}-SNAPSHOT.jar boot.jar
RUN jar -xf boot.jar && rm boot.jar

# The AOT cache refuses a classpath containing a plain directory, so the exploded classes become a jar.
RUN cd BOOT-INF/classes && jar -cf /staging/app.jar . && rm -rf /staging/BOOT-INF/classes


FROM amazoncorretto:25-al2023 AS prewarm

ARG KOTLIN_VERSION
ENV KOTLIN_LIB=$KOTLIN_VERSION
ENV KOTLIN_LIB_JS=${KOTLIN_VERSION}-js

RUN dnf install -y jq && dnf clean all

WORKDIR /kotlin-compiler-server

COPY --from=assemble /staging/BOOT-INF/lib /kotlin-compiler-server/lib
COPY --from=assemble /staging/META-INF /kotlin-compiler-server/META-INF
COPY --from=assemble /staging/app.jar /kotlin-compiler-server/app.jar
COPY ${KOTLIN_VERSION} /kotlin-compiler-server/${KOTLIN_VERSION}
COPY ${KOTLIN_VERSION}-js /kotlin-compiler-server/${KOTLIN_VERSION}-js
COPY kore-prewarm /kore-prewarm
COPY kore-prewarm/snippets /kotlin-compiler-server/anchor
COPY ir-cache-seed /kotlin-compiler-server/ir-cache

RUN /kore-prewarm/classpath.sh

ENV KORE_JS_CACHE_DIRECTORY=/kotlin-compiler-server/ir-cache
ENV KORE_JS_ANCHOR_DIRECTORY=/kotlin-compiler-server/anchor

RUN /kore-prewarm/train.sh


# The trained IR cache alone, which CI exports to seed the next build.
FROM scratch AS ir-cache-export

COPY --from=prewarm /kotlin-compiler-server/ir-cache /


FROM amazoncorretto:25-al2023

# Links the GHCR package to this repository.
LABEL org.opencontainers.image.source="https://github.com/Kore-Minecraft/kore-playground-server"

# For the health check and the boot warmup.
RUN dnf install -y curl-minimal && dnf clean all

WORKDIR /kotlin-compiler-server

COPY --from=prewarm /kotlin-compiler-server /kotlin-compiler-server

ENV PORT=8080
ENV KORE_JS_CACHE_DIRECTORY=/kotlin-compiler-server/ir-cache
ENV KORE_JS_ANCHOR_DIRECTORY=/kotlin-compiler-server/anchor

EXPOSE 8080

CMD ["/kotlin-compiler-server/entrypoint.sh"]
