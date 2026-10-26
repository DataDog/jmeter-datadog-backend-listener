FROM registry.ddbuild.io/images/mirror/maven:3.9.5-eclipse-temurin-8@sha256:497933514f674f16f6fcf0e4e3259195d4bb909f25151dafa05efe68d6ea7cd8
ENV DEBIAN_FRONTEND=noninteractive
RUN apt update
RUN apt install -y python3 python3-pip
RUN python3 -m pip install awscli