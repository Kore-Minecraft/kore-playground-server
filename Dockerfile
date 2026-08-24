# Kore playground compile backend: upstream kotlin-compiler-server plus Kore's JS klibs, a prewarmed
# Kotlin/JS IR build cache and a JDK AOT cache. See README-KORE.md.

FROM amazoncorretto:17-al2023 AS build

ARG KOTLIN_VERSION
ARG DEVELOCITY_ACCESS_KEY

RUN if [ -z "$KOTLIN_VERSION" ]; then \
        echo "Error: KOTLIN_VERSION argument is not set. Use docker-image-build.sh to build the image." >&2; \
        exit 1; \
    fi

ENV DEVELOCITY_ACCESS_KEY=$DEVELOCITY_ACCESS_KEY
ENV KOTLIN_LIB=$KOTLIN_VERSION
ENV KOTLIN_LIB_JS=${KOTLIN_VERSION}-js

RUN yum install -y findutils libatomic && yum clean all

RUN mkdir -p /kotlin-compiler-server
WORKDIR /kotlin-compiler-server
ADD . /kotlin-compiler-server

RUN ./gradlew build -x test
RUN mkdir -p /build/libs && (cd /build/libs;  jar -xf /kotlin-compiler-server/build/libs/kotlin-compiler-server-${KOTLIN_LIB}-SNAPSHOT.jar)

# The AOT cache refuses a classpath containing a plain directory, so the exploded classes become a jar.
RUN cd /build/libs/BOOT-INF/classes && jar -cf /build/app.jar .


# Assembles the runtime layout, then trains both caches against the real server on the real endpoint.
FROM amazoncorretto:25-al2023 AS prewarm

ARG KOTLIN_VERSION
ENV KOTLIN_LIB=$KOTLIN_VERSION
ENV KOTLIN_LIB_JS=${KOTLIN_VERSION}-js

RUN dnf install -y jq && dnf clean all

WORKDIR /kotlin-compiler-server

COPY --from=build /build/libs/BOOT-INF/lib /kotlin-compiler-server/lib
COPY --from=build /build/libs/META-INF /kotlin-compiler-server/META-INF
COPY --from=build /build/app.jar /kotlin-compiler-server/app.jar
COPY --from=build /kotlin-compiler-server/${KOTLIN_LIB} /kotlin-compiler-server/${KOTLIN_LIB}
COPY --from=build /kotlin-compiler-server/${KOTLIN_LIB_JS} /kotlin-compiler-server/${KOTLIN_LIB_JS}
COPY kore-prewarm /kore-prewarm

RUN /kore-prewarm/classpath.sh

ENV KORE_JS_CACHE_DIRECTORY=/kotlin-compiler-server/ir-cache

RUN /kore-prewarm/train.sh


FROM amazoncorretto:25-al2023

# Only for the container health check; the server itself needs nothing beyond the JDK.
RUN dnf install -y curl-minimal && dnf clean all

WORKDIR /kotlin-compiler-server

COPY --from=prewarm /kotlin-compiler-server /kotlin-compiler-server

ENV PORT=8080
ENV KORE_JS_CACHE_DIRECTORY=/kotlin-compiler-server/ir-cache

EXPOSE 8080

CMD ["/kotlin-compiler-server/entrypoint.sh"]
