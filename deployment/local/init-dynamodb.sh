#!/bin/sh
set -e

ENDPOINT="http://dynamodb-local:8000"
REGION="us-east-1"

aws_local() {
  aws --endpoint-url "$ENDPOINT" --region "$REGION" "$@"
}

until aws_local dynamodb list-tables > /dev/null 2>&1; do
  echo "Waiting for DynamoDB Local..."
  sleep 2
done

aws_local dynamodb create-table \
  --table-name tickets \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
      AttributeName=status,AttributeType=S \
      AttributeName=ticketId,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes '[{
      "IndexName": "idx_status",
      "KeySchema": [
        {"AttributeName":"status","KeyType":"HASH"},
        {"AttributeName":"ticketId","KeyType":"RANGE"}
      ],
      "Projection": {"ProjectionType":"ALL"}
  }]' \
  --billing-mode PAY_PER_REQUEST \
  || echo "tickets table already exists"

aws_local dynamodb update-time-to-live \
  --table-name tickets \
  --time-to-live-specification "Enabled=true,AttributeName=reservationExpiresAt" \
  || echo "TTL already configured on tickets table"

aws_local dynamodb create-table \
  --table-name orders \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  || echo "orders table already exists"

# Sample data for manual testing: a ticket still RESERVED for sample-order-1,
# past its reservationExpiresAt, plus its PENDING_CONFIRMATION order item, so a
# reservation-expiry message referencing this order/ticket can be expired end to end.
aws_local dynamodb put-item --table-name tickets --item '{
  "pk": {"S": "sample-event-1"},
  "sk": {"S": "t1"},
  "ticketId": {"S": "t1"},
  "status": {"S": "RESERVED"},
  "orderId": {"S": "sample-order-1"},
  "version": {"N": "1"}
}' || true

aws_local dynamodb put-item --table-name orders --item '{
  "pk": {"S": "sample-order-1"},
  "sk": {"S": "2026-07-01T10:00:00Z"},
  "orderId": {"S": "sample-order-1"},
  "eventId": {"S": "sample-event-1"},
  "ticketIds": {"L": [{"S": "t1"}]},
  "userId": {"S": "sample-user-1"},
  "orderStatus": {"S": "PENDING_CONFIRMATION"},
  "createdAt": {"S": "2026-07-01T10:00:00Z"}
}' || true

echo "DynamoDB Local tables ready"
