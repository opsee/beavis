FROM quay.io/opsee/opsee-java

RUN apk add --update bash curl

RUN mkdir /beavis /beavis/bin /beavis/etc /beavis/lib /beavis/js
RUN mkdir -p /opt/bin
RUN curl -o /opt/bin/ec2-env https://s3-us-west-2.amazonaws.com/opsee-releases/go/ec2-env/ec2-env && \
  chmod 755 /opt/bin/ec2-env
RUN curl -o /opt/bin/s3kms https://s3-us-west-2.amazonaws.com/opsee-releases/go/vinz-clortho/s3kms-linux-amd64 && \
  chmod 755 /opt/bin/s3kms

COPY lib/* /beavis/lib/
COPY bin/* /beavis/bin/
COPY etc/* /beavis/etc/
ADD slate.tar.gz /beavis/js/

ENV NSQ_LOOKUP=""
ENV NSQ_PRODUCE=""
ENV DB_NAME="postgres"
ENV DB_HOST="postgres"
ENV DB_USER="postgres"
ENV DB_PASS=""
ENV ENVIRONMENT="dev"
ENV APPENV="beavisenv"
ENV MANDRILL_API_KEY=""
ENV YELLER_API_KEY=""
ENV OPSEE_HOST=""

EXPOSE 8081

WORKDIR /beavis

ENTRYPOINT ["/beavis/bin/beavis"]
CMD ["start"]
