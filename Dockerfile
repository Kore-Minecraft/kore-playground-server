# Kore playground compile backend: upstream kotlin-compiler-server plus Kore's JS klibs, a prewarmed
# Kotlin/JS IR build cache and a JDK AOT cache. See README-KORE.md.
#
# The Gradle build runs outside this file - docker-image-build.sh locally, the workflow in CI - because a
# Docker layer cannot hold the Gradle dependency and build caches between runs, and re-resolving them is
# most of a cold build. The context must therefore already carry the boot jar and the two klib folders:
#
#   build/libs/kotlin-compiler-server-<KOTLIN_VERSION>-SNAPSHOT.jar
#   <KOTLIN_VERSION>/        JVM klibs
#   <KOTLIN_VERSION>-js/     JS klibs, Kore included
#   ir-cache-seed/           IR cache from a previous build, or empty

FROM amazoncorretto:25-al2023 AS assemble

ARG KOTLIN_VERSION

RUN test -n "$KOTLIN_VERSION" \
	|| { echo "KOTLIN_VERSION build arg is not set, build through docker-image-build.sh" >&2; exit 1; }

WORKDIR /staging
COPY build/libs/kotlin-compiler-server-${KOTLIN_VERSION}-SNAPSHOT.jar boot.jar
RUN jar -xf boot.jar && rm boot.jar

# The AOT cache refuses a classpath containing a plain directory, so the exploded classes become a jar.
RUN cd BOOT-INF/classes && jar -cf /staging/app.jar . && rm -rf /staging/BOOT-INF/classes


# Assembles the runtime layout, then trains both caches against the real server on the real endpoint.
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
# The prewarm snippets double as the anchor module, see KotlinToJSTranslator.anchorKlib.
COPY kore-prewarm/snippets /kotlin-compiler-server/anchor

# A cache from a previous build, so the training run pays for lowerings nobody has reached yet rather than
# for all of them. Empty on a fresh checkout, which only makes the training run slower.
COPY ir-cache-seed /kotlin-compiler-server/ir-cache

RUN /kore-prewarm/classpath.sh

ENV KORE_JS_CACHE_DIRECTORY=/kotlin-compiler-server/ir-cache
ENV KORE_JS_ANCHOR_DIRECTORY=/kotlin-compiler-server/anchor

RUN /kore-prewarm/train.sh


# Carries nothing but the trained IR cache, so CI can pull it back out and seed the next build with it.
FROM scratch AS ir-cache-export

COPY --from=prewarm /kotlin-compiler-server/ir-cache /


FROM amazoncorretto:25-al2023

# Only for the container health check; the server itself needs nothing beyond the JDK.
RUN dnf install -y curl-minimal && dnf clean all

WORKDIR /kotlin-compiler-server

COPY --from=prewarm /kotlin-compiler-server /kotlin-compiler-server

ENV PORT=8080
ENV KORE_JS_CACHE_DIRECTORY=/kotlin-compiler-server/ir-cache
ENV KORE_JS_ANCHOR_DIRECTORY=/kotlin-compiler-server/anchor

EXPOSE 8080

CMD ["/kotlin-compiler-server/entrypoint.sh"]
