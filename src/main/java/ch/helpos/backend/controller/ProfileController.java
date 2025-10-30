package ch.helpos.backend.controller;

import ch.helpos.backend.models.Profile;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.neo4j.driver.Driver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/profile")
public class ProfileController extends AbstractController {

    public ProfileController(MongoDatabase mongoDatabase, Driver neo4jDriver) {
        super(mongoDatabase, neo4jDriver);
    }

    @PostMapping
    public ResponseEntity<Profile> createProfile(@RequestBody Profile profile) {
        MongoCollection<Document> profiles = mongoDatabase.getCollection("profiles");
        Document doc = new Document("name", profile.getName())
                .append("biologicalSex", profile.getBiologicalSex())
                .append("dateOfBirth", profile.getDateOfBirth())
                .append("countryRequested", profile.getCountryRequested());
        profiles.insertOne(doc);

        String id = doc.getObjectId("_id").toString();
        createNode("Profile", id);

        Profile created = Profile.builder()
                .id(id)
                .name(doc.getString("name"))
                .biologicalSex(doc.getString("biologicalSex"))
                .dateOfBirth(doc.getString("dateOfBirth"))
                .countryRequested(doc.getString("countryRequested"))
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
    
}
