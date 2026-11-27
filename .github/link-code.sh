#!/bin/sh
# Test helper: makes a link code holding one title and the name "Marvelous", and prints it.
H=https://movio-helper.g41us-jarvis.workers.dev
CODE=$(curl -s -X POST $H/sync/new | sed 's/.*"code":"\([A-Z0-9]*\)".*/\1/')
NOW=$(date +%s000)
curl -s -X PUT $H/sync/$CODE -d "{\"list\":{\"movie:550\":{\"at\":$NOW,\"t\":{\"id\":550,\"kind\":\"movie\",\"name\":\"Fight Club\"}}},\"cont\":{},\"pos\":{},\"prof\":{\"at\":$NOW,\"name\":\"Marvelous\"}}" > /dev/null
echo $CODE
