#!/bin/bash

docker exec -it gemp_app_1 mvn install -Dmaven.test.skip=true
docker exec -it gemp_app_1 mvn antrun:run@clean-models -N
docker exec -it gemp_app_1 mvn antrun:run@clean-training-data -N
docker compose restart
