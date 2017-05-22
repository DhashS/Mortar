FROM hseeberger/scala-sbt

COPY * /root/

RUN apt update && apt upgrade -y

RUN apt install -y \
    openssh-server \
    rdiff-backup \
    duplicity \
    attic \
    python3 \


RUN cd /root

RUN sbt compile

ENTRYPOINT ["sbt", "run"]
