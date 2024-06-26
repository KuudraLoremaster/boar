package dev.boarbot.entities.boaruser;

import dev.boarbot.BoarBotApp;
import dev.boarbot.bot.config.BotConfig;
import dev.boarbot.util.boar.BoarObtainType;
import dev.boarbot.util.boar.BoarUtil;
import dev.boarbot.util.data.DataUtil;
import dev.boarbot.util.time.TimeUtil;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.entities.User;

import java.sql.*;
import java.util.*;

@Log4j2
public class BoarUser {
    private final BotConfig config = BoarBotApp.getBot().getConfig();

    @Getter private final User user;
    @Getter private final String userID;

    private boolean alreadyAdded = false;
    private boolean isFirstDaily = false;

    private volatile int numRefs = 0;

    public BoarUser(User user) throws SQLException {
        this.user = user;
        this.userID = user.getId();
        this.incRefs();
    }

    private void addUser(Connection connection) throws SQLException {
        if (this.alreadyAdded) {
            return;
        }

        String query = """
            INSERT IGNORE INTO users (user_id, username) VALUES (?, ?)
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.userID);
            statement.setString(2, this.user.getName());
            statement.execute();
        }

        this.alreadyAdded = true;
    }

    private synchronized void updateUser(Connection connection) throws SQLException {
        String query = """
            SELECT last_daily_timestamp
            FROM users
            WHERE user_id = ?;
        """;

        boolean resetStreak = false;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.userID);

            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    Timestamp lastDailyTimestamp = results.getTimestamp("last_daily_timestamp");

                    resetStreak = lastDailyTimestamp != null && lastDailyTimestamp.before(
                        new Timestamp(TimeUtil.getLastDailyResetMilli() - this.config.getNumberConfig().getOneDay())
                    );
                }
            }
        }

        if (resetStreak) {
            query = """
                UPDATE users
                SET boar_streak = 0
                WHERE user_id = ?
            """;

            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, this.userID);
                statement.executeUpdate();
            }
        }
    }

    public synchronized void passSynchronizedAction(Synchronizable callingObject) {
        callingObject.doSynchronizedAction(this);
    }

    public void addBoars(
        List<String> boarIDs,
        Connection connection,
        BoarObtainType obtainType,
        List<Integer> bucksGotten,
        List<Integer> boarEditions
    ) throws SQLException {
        this.addUser(connection);

        List<String> newBoarIDs = new ArrayList<>();

        if (this.isFirstDaily) {
            this.giveFirstBonus(connection);
        }
        this.isFirstDaily = false;

        for (String boarID : boarIDs) {
            String boarAddQuery = """
                INSERT INTO collected_boars (user_id, boar_id, original_obtain_type)
                VALUES (?, ?, ?)
                RETURNING edition, bucks_gotten;
            """;
            int curEdition;

            try (PreparedStatement boarAddStatement = connection.prepareStatement(boarAddQuery)) {
                boarAddStatement.setString(1, this.userID);
                boarAddStatement.setString(2, boarID);
                boarAddStatement.setString(3, obtainType.toString());

                try (ResultSet results = boarAddStatement.executeQuery()) {
                    if (results.next()) {
                        curEdition = results.getInt("edition");

                        newBoarIDs.add(boarID);
                        boarEditions.add(curEdition);
                        bucksGotten.add(results.getInt("bucks_gotten"));

                        String rarityKey = BoarUtil.findRarityKey(boarID);

                        if (curEdition == 1 && this.config.getRarityConfigs().get(rarityKey).isGivesSpecial()) {
                            this.addFirstBoar(newBoarIDs, connection, bucksGotten, boarEditions);
                        }
                    }
                }
            }
        }

        boarIDs.clear();
        boarIDs.addAll(newBoarIDs);
    }

    private void addFirstBoar(
        List<String> newBoarIDs,
        Connection connection,
        List<Integer> bucksGotten,
        List<Integer> boarEditions
    ) throws SQLException {
        String insertFirstQuery = """
            INSERT INTO collected_boars (user_id, boar_id, original_obtain_type)
            VALUES (?, ?, ?)
            RETURNING edition;
        """;
        String firstBoarID = this.config.getStringConfig().getFirstBoarID();

        if (!this.config.getItemConfig().getBoars().containsKey(firstBoarID)) {
            return;
        }

        try (PreparedStatement insertFirstStatement = connection.prepareStatement(insertFirstQuery)) {
            insertFirstStatement.setString(1, this.userID);
            insertFirstStatement.setString(2, firstBoarID);
            insertFirstStatement.setString(3, BoarObtainType.OTHER.toString());

            try (ResultSet results = insertFirstStatement.executeQuery()) {
                if (results.next()) {
                    newBoarIDs.add(firstBoarID);
                    boarEditions.add(results.getInt("edition"));
                    bucksGotten.add(0);
                }
            }
        }
    }

    public boolean canUseDaily(Connection connection) throws SQLException {
        this.addUser(connection);

        boolean canUseDaily = false;
        String query = """
            SELECT last_daily_timestamp
            FROM users
            WHERE user_id = ?;
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.userID);

            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    Timestamp lastDailyTimestamp = results.getTimestamp("last_daily_timestamp");

                    canUseDaily = lastDailyTimestamp == null || lastDailyTimestamp.before(
                        new Timestamp(TimeUtil.getLastDailyResetMilli())
                    );

                    this.isFirstDaily = lastDailyTimestamp == null;
                }
            }
        }

        return canUseDaily;
    }

    private void giveFirstBonus(Connection connection) throws SQLException {
        this.insertPowerupIfNotExist(connection, "miracle");
        this.insertPowerupIfNotExist(connection, "gift");

        String updateQuery = """
            UPDATE collected_powerups
            SET amount = CASE
                WHEN powerup_id = ? THEN amount + 5
                WHEN powerup_id = ? THEN amount + 1
            END
            WHERE user_id = ? AND powerup_id IN (?, ?);
        """;

        try (PreparedStatement statement = connection.prepareStatement(updateQuery)) {
            statement.setString(1, "miracle");
            statement.setString(2, "gift");
            statement.setString(3, this.userID);
            statement.setString(4, "miracle");
            statement.setString(5, "gift");
            statement.execute();
        }
    }

    private void insertPowerupIfNotExist(Connection connection, String powerupID) throws SQLException {
        String query = """
            INSERT INTO collected_powerups (user_id, powerup_id)
            SELECT ?, ?
            WHERE NOT EXISTS (
                SELECT unique_id
                FROM collected_powerups
                WHERE user_id = ? AND powerup_id = ?
            );
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.userID);
            statement.setString(2, powerupID);
            statement.setString(3, this.userID);
            statement.setString(4, powerupID);
            statement.execute();
        }
    }

    public boolean isFirstDaily() {
        return this.isFirstDaily;
    }

    public long getMultiplier(Connection connection) throws SQLException {
        return this.getMultiplier(connection, 0);
    }

    public long getMultiplier(Connection connection, int extraActive) throws SQLException {
        long multiplier = 0;

        String multiplierQuery = """
            SELECT multiplier, miracles_active
            FROM users
            WHERE user_id = ?;
        """;

        try (PreparedStatement multiplierStatement = connection.prepareStatement(multiplierQuery)) {
            multiplierStatement.setString(1, this.userID);

            try (ResultSet results = multiplierStatement.executeQuery()) {
                if (results.next()) {
                    int miraclesActive = results.getInt("miracles_active");
                    multiplier = results.getLong("multiplier");
                    int miracleIncreaseMax = this.config.getNumberConfig().getMiracleIncreaseMax();

                    int activesLeft = miraclesActive+extraActive;
                    for (; activesLeft>0; activesLeft--) {
                        long amountToAdd = (long) Math.min(Math.ceil(multiplier * 0.1), miracleIncreaseMax);

                        if (amountToAdd == this.config.getNumberConfig().getMiracleIncreaseMax()) {
                            break;
                        }

                        multiplier += amountToAdd;
                    }

                    multiplier += (long) activesLeft * miracleIncreaseMax;
                }
            }
        }

        return multiplier;
    }

    public void setNotifications(Connection connection, String channelID) throws SQLException {
        String query = """
            UPDATE users
            SET notifications_on = ?, notification_channel = ?
            WHERE user_id = ?
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setBoolean(1, channelID != null);
            statement.setString(2, channelID);
            statement.setString(3, this.userID);
            statement.executeUpdate();
        }
    }

    public boolean getNotificationStatus(Connection connection) throws SQLException {
        String query = """
            SELECT notifications_on
            FROM users
            WHERE user_id = ?;
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.userID);

            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    return results.getBoolean("notifications_on");
                }
            }
        }

        return false;
    }

    public int getPowerupAmount(Connection connection, String powerupID) throws SQLException {
        String query = """
            SELECT amount
            FROM collected_powerups
            WHERE user_id = ? AND powerup_id = ?;
        """;

        int amount = 0;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.userID);
            statement.setString(2, powerupID);

            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    amount = results.getInt("amount");
                }
            }
        }

        return amount;
    }

    public void usePowerup(Connection connection, String powerupID, int amount) throws SQLException {
        String query = """
            UPDATE collected_powerups
            SET amount = amount - ?, amount_used = amount_used + ?
            WHERE user_id = ? AND powerup_id = ?;
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, amount);
            statement.setInt(2, amount);
            statement.setString(3, this.userID);
            statement.setString(4, powerupID);
            statement.executeUpdate();
        }
    }

    public void activateMiracles(Connection connection, int amount) throws SQLException {
        String query = """
            UPDATE users
            SET miracles_active = miracles_active + ?
            WHERE user_id = ?;
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, amount);
            statement.setString(2, this.userID);
            statement.executeUpdate();
        }

        this.usePowerup(connection, "miracle", amount);
    }

    public void useActiveMiracles(Connection connection) throws SQLException {
        String query = """
            UPDATE users
            SET miracles_active = 0
            WHERE user_id = ?;
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.userID);
            statement.executeUpdate();
        }
    }

    public synchronized void incRefs() throws SQLException {
        this.numRefs++;

        try (Connection connection = DataUtil.getConnection()) {
            this.updateUser(connection);
        }
    }

    public synchronized void decRefs() {
        this.numRefs--;

        if (this.numRefs == 0) {
            BoarUserFactory.removeBoarUser(this.userID);
        }
    }
}
