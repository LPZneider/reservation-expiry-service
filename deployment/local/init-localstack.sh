#!/bin/sh
set -e

REGION="us-east-1"

awslocal sqs create-queue --queue-name reservation-expiry --region "$REGION"

# Sample message matching the seeded RESERVED ticket/order in init-dynamodb.sh, so a
# fresh docker-compose up exercises the full expiration flow without any manual step.
# No DelaySeconds here (unlike the real publisher) so it's immediately visible for
# local testing instead of waiting 600s.
awslocal sqs send-message \
  --queue-url "http://localhost:4566/000000000000/reservation-expiry" \
  --region "$REGION" \
  --message-body '{"orderId":"sample-order-1","ticketIds":["t1"]}'

echo "LocalStack queues ready"
