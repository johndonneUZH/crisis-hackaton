package ch.helpos.backend.controller;

import com.mongodb.client.MongoDatabase;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

public abstract class AbstractController {
    protected final MongoDatabase mongoDatabase;
    protected final Driver neo4jDriver;

    protected AbstractController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        this.mongoDatabase = mongoDatabase;
        this.neo4jDriver = neo4jDriver;
    }

    /** Create a Neo4j node with given label and id */
    protected void createNode(String label, String id) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE (n:" + label + " {id: $id})", Values.parameters("id", id));
                return null; // executeWrite expects a return
            });
        }
    }

    /** Create a Neo4j relationship between two nodes by id */
    protected void createRelation(String fromLabel, String fromId, String relType, String toLabel, String toId) {
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (a:%s {id: $fromId}), (b:%s {id: $toId})
                    CREATE (a)-[r:%s]->(b)
                    """.formatted(fromLabel, toLabel, relType),
                    Values.parameters("fromId", fromId, "toId", toId));
                return null;
            });
        }
    }
}
