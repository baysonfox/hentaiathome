FROM ubuntu:22.04
RUN mkdir -p /home/hah
VOLUME ["/home/hah"]
WORKDIR "/home/hah"
ENTRYPOINT ["/hah-server"]
COPY --chown=0:0 build/HentaiAtHome /hah-server
