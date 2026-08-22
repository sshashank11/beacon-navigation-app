import {
  defineRailway,
  github,
  image,
  preserve,
  project,
  redis,
  service,
  volume,
} from "railway/iac";

const REGION = "us-east4-eqdc4a";
const REPOSITORY = "sshashank11/beacon-navigation-app";

export default defineRailway(() => {
  const cache = redis("Redis", { region: REGION });
  cache.deploy = {
    startCommand:
      "/bin/sh -c \"rm -rf $RAILWAY_VOLUME_MOUNT_PATH/lost+found/ && exec docker-entrypoint.sh redis-server --requirepass $REDIS_PASSWORD --save 60 1 --dir $RAILWAY_VOLUME_MOUNT_PATH\"",
  };

  // Runtime exports omit pipeline staging data and fit the Trial volume limit.
  const databaseData = volume("postgis-data", {
    allowOnlineResize: true,
    region: REGION,
    sizeMB: 500,
  });
  const graphData = volume("beacon-api-volume", {
    allowOnlineResize: true,
    region: REGION,
    sizeMB: 500,
  });

  const postgis = service("postgis", {
    source: image("postgis/postgis:16-3.4"),
    replicas: { [REGION]: 1 },
    volumeMounts: {
      "/var/lib/postgresql/data": databaseData,
    },
    env: {
      DATABASE_URL: preserve(),
      PGDATA: preserve(),
      PGDATABASE: preserve(),
      PGHOST: preserve(),
      PGPASSWORD: preserve(),
      PGPORT: preserve(),
      PGUSER: preserve(),
      POSTGRES_DB: preserve(),
      POSTGRES_PASSWORD: preserve(),
      POSTGRES_USER: preserve(),
    },
  });

  const api = service("beacon-api", {
    source: github(REPOSITORY, { branch: "main" }),
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "Dockerfile",
      watchPatterns: [
        "/api/**",
        "/data/osm/.gitkeep",
        "/docker/**",
        "/Dockerfile",
        "/.dockerignore",
      ],
    },
    deploy: {
      healthcheckPath: "/actuator/health",
      healthcheckTimeout: 300,
      restartPolicyMaxRetries: 3,
    },
    replicas: { [REGION]: 1 },
    volumeMounts: {
      "/data": graphData,
    },
    env: {
      BEACON_ROUTING_ENABLED: preserve(),
      DATABASE_URL: "jdbc:postgresql://postgis.railway.internal:5432/beacon",
      OSM_EXTRACT_URL: preserve(),
      PORT: preserve(),
      POSTGRES_PASSWORD: postgis.env.POSTGRES_PASSWORD,
      POSTGRES_USER: postgis.env.POSTGRES_USER,
      RAILWAY_RUN_UID: preserve(),
      REDIS_URL: cache.env.REDIS_URL,
    },
  });

  const web = service("beacon-web", {
    source: github(REPOSITORY, { branch: "main", rootDirectory: "web" }),
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "Dockerfile",
      watchPatterns: ["/web/**"],
    },
    deploy: {
      healthcheckPath: "/health",
      healthcheckTimeout: 30,
      restartPolicyMaxRetries: 3,
    },
    replicas: { [REGION]: 1 },
    env: {
      API_UPSTREAM: "http://beacon-api.railway.internal:8080",
      PORT: preserve(),
    },
  });

  return project("beacon-navigation", {
    resources: [web, api, postgis, cache, graphData, databaseData],
  });
});
