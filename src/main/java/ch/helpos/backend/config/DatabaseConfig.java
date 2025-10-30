package ch.helpos.backend.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(@Value("${helpos.mongo.uri:mongodb://localhost:27017}") String mongoUri) {
        return MongoClients.create(mongoUri);
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
            @Value("${helpos.neo4j.uri:bolt://localhost:7687}") String neo4jUri,
            @Value("${helpos.neo4j.username:neo4j}") String username,
            @Value("${helpos.neo4j.password:password}") String password
    ) {
        return GraphDatabase.driver(neo4jUri, AuthTokens.basic(username, password));
    }
}
