package com.avairebot.scheduler.tasks;

import com.avairebot.AvaIre;
import com.avairebot.Constants;
import com.avairebot.contracts.scheduler.Task;
import com.avairebot.database.controllers.PlayerController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class SyncPlayerUpdateReferencesWithDatabaseTask implements Task {

    private static final Logger log = LoggerFactory.getLogger(SyncPlayerUpdateReferencesWithDatabaseTask.class);

    @Override
    public void handle(AvaIre avaire) {
        if (PlayerController.getPlayerQueue().isEmpty()) {
            return;
        }

        Map<Long, PlayerController.PlayerUpdateReference> playerQueue;
        synchronized (PlayerController.getPlayerQueue()) {
            playerQueue = new HashMap<>(PlayerController.getPlayerQueue());
            PlayerController.getPlayerQueue().clear();
        }

        Connection connection = null;
        try {
            connection = avaire.getDatabase().getConnection().getConnection();
            String query = String.format("UPDATE `%s` SET `avatar` = ?, `username` = ?, `discriminator` = ? WHERE `user_id` = ?",
                Constants.PLAYER_EXPERIENCE_TABLE_NAME
            );

            log.debug("Starting \"Player Reference\" update task with query: " + query);

            boolean autoCommit = connection.getAutoCommit();
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                connection.setAutoCommit(false);

                for (Map.Entry<Long, PlayerController.PlayerUpdateReference> entity : playerQueue.entrySet()) {
                    preparedStatement.setString(1, entity.getValue().getAvatar());
                    preparedStatement.setString(2, entity.getValue().getUsername());
                    preparedStatement.setString(3, entity.getValue().getDiscriminator());
                    preparedStatement.setString(4, entity.getKey().toString());
                    preparedStatement.addBatch();
                }

                preparedStatement.executeBatch();
                connection.commit();
            }

            if (connection.getAutoCommit() != autoCommit) {
                connection.setAutoCommit(autoCommit);
            }

            log.debug("Finished \"Player Reference\" task, updated {} records in the process", playerQueue.size());
        } catch (SQLException e) {
            logSQLException(e);
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException e1) {
                    logSQLException(e1);
                }
            }
        }
    }

    private void logSQLException(SQLException e) {
        log.error("An SQL exception was thrown while updating player references: ", e);
    }
}
