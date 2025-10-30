package ch.helpos.backend.controller;

import ch.helpos.backend.models.Topic;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.neo4j.driver.Driver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/topics")
public class TopicController extends AbstractController {

    public TopicController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
    }

    private Topic mapDocToTopic(Document doc) {
        return Topic.builder()
                .id(doc.getObjectId("_id").toString())
                .name(doc.getString("name"))
                .description(doc.getString("description"))
                .build();
    }

    @PostMapping
    public ResponseEntity<Topic> createTopic(@RequestBody Topic topic) {
        MongoCollection<Document> topics = mongoDatabase.getCollection("topics");
        Document doc = new Document("name", topic.getName())
                .append("description", topic.getDescription());
        topics.insertOne(doc);

        String id = doc.getObjectId("_id").toString();
        createNode("Topic", id);

        Topic created = mapDocToTopic(doc);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<Topic>> getAllTopics() {
        MongoCollection<Document> topicsCollection = mongoDatabase.getCollection("topics");
        List<Topic> topicsList = new ArrayList<>();

        for (Document doc : topicsCollection.find()) {
            topicsList.add(mapDocToTopic(doc));
        }

        return ResponseEntity.ok(topicsList);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Topic> getTopicById(@PathVariable String id) {
        MongoCollection<Document> topicsCollection = mongoDatabase.getCollection("topics");
        Document doc;
        try {
            doc = topicsCollection.find(new Document("_id", new ObjectId(id))).first();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid topic id");
        }

        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Topic not found");
        }

        return ResponseEntity.ok(mapDocToTopic(doc));
    }
}
