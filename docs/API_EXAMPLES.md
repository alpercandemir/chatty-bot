# HTTP walkthrough

All requests are local and synthetic. Swagger generates the implemented OpenAPI contract at `/v3/api-docs`; both it and Swagger UI require a presenter cookie. The browser opens the studio before following the API docs link.

```sh
curl -c /tmp/chatty-demo.cookies -H 'Content-Type: application/json' -d '{}' http://localhost:8080/api/v1/demo/presenter
curl -b /tmp/chatty-demo.cookies -c /tmp/chatty-demo.cookies -H 'Content-Type: application/json' -d '{"pack":"commerce","principalId":"customer-aylin"}' http://localhost:8080/api/v1/demo/sessions
curl -b /tmp/chatty-demo.cookies -H 'Content-Type: application/json' -d '{"mode":"scripted"}' http://localhost:8080/api/v1/conversations
```

Use the returned conversation ID:

```sh
curl -b /tmp/chatty-demo.cookies -H 'Content-Type: application/json' -d '{"clientMessageId":"demo-message-1","text":"Cancel order NS-1001."}' http://localhost:8080/api/v1/conversations/CONVERSATION_ID/messages
curl -N -b /tmp/chatty-demo.cookies http://localhost:8080/api/v1/runs/RUN_ID/events
curl -b /tmp/chatty-demo.cookies http://localhost:8080/api/v1/conversations/CONVERSATION_ID
curl -b /tmp/chatty-demo.cookies -X POST http://localhost:8080/api/v1/actions/ACTION_ID/confirm
curl -b /tmp/chatty-demo.cookies http://localhost:8080/internal/demo/v1/orders/NS-1001
```

Messages return 202. Action cards appear in conversation snapshots; tools cannot choose identity or confirm actions. Internal mutation contracts validate action/operation/resource/parameter/header bindings and replay confirmed results; internal callers cannot dispatch an unconfirmed proposal. The harness calls typed in-process scenario services under the same transaction rather than HTTP-looping into itself.

The fixture defines eight orders; date offsets anchor when a workspace is first created or reset. An address is copied into each order so one change does not alter another order or customer profile. NS-1007 and NS-9999 give the same neutral 404 to Aylin. Payment/refund fields are synthetic; success never implies money moved.
