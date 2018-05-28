package com.avairebot.commands.utility;

import com.avairebot.AvaIre;
import com.avairebot.Constants;
import com.avairebot.cache.CacheType;
import com.avairebot.chat.SimplePaginator;
import com.avairebot.commands.CommandHandler;
import com.avairebot.commands.CommandMessage;
import com.avairebot.commands.administration.LevelCommand;
import com.avairebot.contracts.commands.CacheFingerprint;
import com.avairebot.contracts.commands.ThreadCommand;
import com.avairebot.database.collection.Collection;
import com.avairebot.database.collection.DataRow;
import com.avairebot.database.transformers.GuildTransformer;
import com.avairebot.utilities.LevelUtil;
import com.avairebot.utilities.NumberUtil;
import net.dv8tion.jda.core.entities.Member;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@CacheFingerprint(name = "leaderboard-command")
public class LeaderboardCommand extends ThreadCommand {

    public LeaderboardCommand(AvaIre avaire) {
        super(avaire, false);
    }

    @Override
    public String getName() {
        return "Leaderboard Command";
    }

    @Override
    public String getDescription() {
        return "Displays the server's level leaderboard with the user's name, rank, level and XP. The response is paginated to show 10 users per page.";
    }

    @Override
    public List<String> getTriggers() {
        return Arrays.asList("leaderboard", "top");
    }

    @Override
    public List<String> getMiddleware() {
        return Collections.singletonList("throttle:channel,2,5");
    }

    @Override
    @SuppressWarnings("ConstantConditions")
    public boolean onCommand(CommandMessage context, String[] args) {
        GuildTransformer transformer = context.getGuildTransformer();
        if (transformer == null || !transformer.isLevels()) {
            return sendErrorMessage(
                context,
                "errors.requireLevelFeatureToBeEnabled",
                CommandHandler.getCommand(LevelCommand.class)
                    .getCommand().generateCommandTrigger(context.getMessage())
            );
        }

        Collection collection = loadTop100From(context);
        if (collection == null) {
            context.makeWarning(context.i18n("noData")).queue();
            return false;
        }

        List<String> messages = new ArrayList<>();
        SimplePaginator paginator = new SimplePaginator(collection.getItems(), 10);
        if (args.length > 0) {
            paginator.setCurrentPage(NumberUtil.parseInt(args[0], 1));
        }

        paginator.forEach((index, key, val) -> {
            DataRow row = (DataRow) val;

            Member member = context.getGuild().getMemberById(row.getLong("user_id"));
            String username = row.getString("username") + "#" + row.getString("discriminator");
            if (member != null) {
                username = member.getUser().getName() + "#" + member.getUser().getDiscriminator();
            }

            long experience = row.getLong("experience", 100);

            messages.add(context.i18n("line")
                .replace(":num", "" + (index + 1))
                .replace(":username", username)
                .replace(":level", "" + LevelUtil.getLevelFromExperience(experience))
                .replace(":experience", "" + (experience - 100))
            );
        });

        messages.add("\n" + paginator.generateFooter(generateCommandTrigger(context.getMessage())));

        context.makeInfo(String.join("\n", messages))
            .setTitle(String.format(
                context.i18n("title"),
                context.getGuild().getName()
            ), "https://avairebot.com/leaderboard/" + context.getGuild().getId())
            .queue();

        return true;
    }

    private Collection loadTop100From(CommandMessage context) {
        return (Collection) avaire.getCache().getAdapter(CacheType.MEMORY).remember("database-xp-leaderboard." + context.getGuild().getId(), 60, () -> {
            try {
                return avaire.getDatabase().newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                    .where("guild_id", context.getGuild().getId())
                    .orderBy("experience", "desc")
                    .take(100)
                    .get();
            } catch (SQLException e) {
                AvaIre.getLogger().error("Failed to fetch leaderboard data for server: " + context.getGuild().getId(), e);
                return null;
            }
        });
    }
}
