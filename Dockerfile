FROM hseeberger/scala-sbt

COPY * /root/

RUN cd /root

RUN sbt compile

ENTRYPOINT ["sbt", "run"]
