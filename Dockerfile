FROM ubuntu:latest
ENV DEBIAN_FRONTEND=noninteractive
RUN apt update
RUN apt install -y openjdk-8-jdk maven python3 python3-pip
ENV JAVA_HOME dirname $(dirname $(readlink -f $(which javac)))
RUN export JAVA_HOME
RUN python3 -m pip install awscli