package ch.helpos.backend.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class DatabaseConfig {

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(@Value("${helpos.mongo.uri:mongodb://localhost:27017}") String mongoUri) {
        ConnectionString connectionString = new ConnectionString(mongoUri);
        MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
                .applyConnectionString(connectionString);

        // MongoDB Atlas requires Stable API versioning for newer clusters
        settingsBuilder.serverApi(ServerApi.builder()
                .version(ServerApiVersion.V1)
                .build());

        return MongoClients.create(settingsBuilder.build());
    }

    @Bean
    public MongoDatabase mongoDatabase(
            MongoClient mongoClient,
            @Value("${helpos.mongo.database:helpos}") String databaseName
    ) {
        return mongoClient.getDatabase(databaseName);
    }

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${helpos.neo4j.uri:neo4j+s://localhost:7687}") String neo4jUri,
            @Value("${helpos.neo4j.username:neo4j}") String username,
            @Value("${helpos.neo4j.password:password}") String password
    ) {
        String normalizedUri = neo4jUri;
        if (neo4jUri.contains("databases.neo4j.io")) {
            if (neo4jUri.startsWith("neo4j://")) {
                normalizedUri = neo4jUri.replace("neo4j://", "neo4j+s://");
            } else if (neo4jUri.startsWith("bolt://")) {
                normalizedUri = neo4jUri.replace("bolt://", "bolt+s://");
            }
        }

        Config config = Config.builder()
                .withConnectionTimeout(30, TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(30, TimeUnit.SECONDS)
                .withMaxConnectionLifetime(10, TimeUnit.MINUTES)
                .withMaxConnectionPoolSize(50)
                .build();

        return GraphDatabase.driver(normalizedUri, AuthTokens.basic(username, password), config);
    }
}
