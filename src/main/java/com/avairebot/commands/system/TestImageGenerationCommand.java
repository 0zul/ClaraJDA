/*
 * Copyright (c) 2018.
 *
 * This file is part of AvaIre.
 *
 * AvaIre is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AvaIre is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AvaIre.  If not, see <https://www.gnu.org/licenses/>.
 *
 *
 */

package com.avairebot.commands.system;

import com.avairebot.AvaIre;
import com.avairebot.Constants;
import com.avairebot.commands.CommandHandler;
import com.avairebot.commands.CommandMessage;
import com.avairebot.commands.administration.LevelCommand;
import com.avairebot.commands.utility.RankCommand;
import com.avairebot.contracts.commands.SystemCommand;
import com.avairebot.database.collection.Collection;
import com.avairebot.database.collection.DataRow;
import com.avairebot.database.controllers.PlayerController;
import com.avairebot.database.transformers.GuildTransformer;
import com.avairebot.database.transformers.PlayerTransformer;
import com.avairebot.imagegen.RankBackgrounds;
import com.avairebot.imagegen.renders.RankBackgroundRender;
import com.avairebot.language.I18n;
import com.avairebot.utilities.CacheUtil;
import com.avairebot.utilities.MentionableUtil;
import com.avairebot.utilities.NumberUtil;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.User;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings({"Duplicates", "ConstantConditions"})
public class TestImageGenerationCommand extends SystemCommand {

    public TestImageGenerationCommand(AvaIre avaire) {
        super(avaire, false);
    }

    @Override
    public String getName() {
        return "Test Image Generation Command";
    }

    @Override
    public String getDescription() {
        return "Tests the image generation for the rank command coming soon~ish for Ava.";
    }

    @Override
    public List<String> getUsageInstructions() {
        return Arrays.asList(
            "`:command` - Lists the available backgrounds",
            "`:command <background id>` - Generates the rank image with the given id"
        );
    }

    @Override
    public List<String> getTriggers() {
        return Collections.singletonList("rank-gen");
    }

    @Override
    public boolean onCommand(CommandMessage context, String[] args) {
        if (args.length == 0) {
            List<String> strings = new ArrayList<>();
            for (RankBackgrounds background : RankBackgrounds.values()) {
                strings.add(I18n.format("`{0}` {1}",
                    background.getId(), background.name()
                ));
            }
            context.makeInfo(String.join(", ", strings)).queue();
            return true;
        }

        RankBackgrounds background = RankBackgrounds.fromId(NumberUtil.parseInt(args[0], -1));
        if (background == null) {
            return sendErrorMessage(context, "Invalid rank background ID given.");
        }

        context.setI18nCommandPrefix(
            CommandHandler.getCommand(RankCommand.class)
        );

        return runRankCommandCode(context, background, Arrays.copyOfRange(args, 1, args.length));
    }

    private boolean runRankCommandCode(CommandMessage context, RankBackgrounds background, String[] args) {
        GuildTransformer guildTransformer = context.getGuildTransformer();
        if (guildTransformer == null || !guildTransformer.isLevels()) {
            return sendErrorMessage(
                context,
                "errors.requireLevelFeatureToBeEnabled",
                CommandHandler.getCommand(LevelCommand.class)
                    .getCommand().generateCommandTrigger(context.getMessage())
            );
        }

        User user = context.getAuthor();
        if (args.length > 0 && !args[0].equals("---skip-mentions")) {
            user = MentionableUtil.getUser(context, new String[]{String.join(" ", args)});
            if (user == null) {
                user = context.getAuthor();
            }
        }
        final User author = user;

        if (author.isBot()) {
            context.makeWarning(context.i18n("botsCannotReceiveXp")).queue();
            return false;
        }

        loadProperties(context, author).thenAccept(properties -> {
            long zeroExperience = avaire.getLevelManager().getExperienceFromLevel(guildTransformer, 0) - 100;
            long experience = properties.getPlayer().getExperience() + zeroExperience;

            long level = avaire.getLevelManager().getLevelFromExperience(guildTransformer, experience);
            long current = avaire.getLevelManager().getExperienceFromLevel(guildTransformer, level);

            long nextLevelXp = avaire.getLevelManager().getExperienceFromLevel(guildTransformer, level + 1);
            double percentage = ((double) (experience - current) / (nextLevelXp - current)) * 100;

            RankBackgroundRender render = new RankBackgroundRender(context.getAuthor())
                .setBackground(background)
                .setCurrentXpInLevel(NumberUtil.formatNicely(nextLevelXp - experience))
                .setTotalXpInLevel(NumberUtil.formatNicely(nextLevelXp - current))
                .setGlobalExperience(NumberUtil.formatNicely(properties.getTotal()))
                .setServerExperience(NumberUtil.formatNicely(experience - zeroExperience - 100))
                .setLevel(NumberUtil.formatNicely(level))
                .setRank(properties.getScore())
                .setPercentage(percentage);

            MessageBuilder message = new MessageBuilder();
            EmbedBuilder embed = new EmbedBuilder()
                .setImage("attachment://rank-background.png")
                .setColor(background.getBackgroundColors().getExperienceForegroundColor());
            message.setEmbed(embed.build());
            try {
                context.getMessageChannel().sendFile(
                    new ByteArrayInputStream(render.renderToBytes()),
                    "rank-background.png", message.build()
                ).queue();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        return true;
    }

    private CompletableFuture<DatabaseProperties> loadProperties(CommandMessage context, User author) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                PlayerTransformer player = context.getAuthor().getIdLong() == author.getIdLong()
                    ? context.getPlayerTransformer() : PlayerController.fetchPlayer(avaire, context.getMessage(), author);

                DataRow data = avaire.getDatabase().newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                    .selectRaw("sum(`experience`) - (count(`user_id`) * 100) as `total`")
                    .where("user_id", author.getId())
                    .get().first();

                long total = data == null ? (player == null ? 0 : player.getExperience()) : data.getLong("total");

                return new DatabaseProperties(player, total, getScore(context, author.getId()));
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    private String getScore(CommandMessage context, String userId) throws SQLException {
        Collection users = (Collection) CacheUtil.getUncheckedUnwrapped(RankCommand.cache, context.getGuild().getIdLong(), () ->
            avaire.getDatabase().newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                .select("user_id as id")
                .orderBy("experience", "desc")
                .where("guild_id", context.getGuild().getId())
                .get()
        );

        for (int i = 0; i < users.size(); i++) {
            if (Objects.equals(users.get(i).getString("id"), userId)) {
                return "" + (i + 1);
            }
        }

        return context.i18n("unranked");
    }

    private long getUsersInGuild(Guild guild) {
        return guild.getMembers().stream().filter(member -> !member.getUser().isBot()).count();
    }

    private class DatabaseProperties {

        private final PlayerTransformer player;
        private final long total;
        private final String score;

        DatabaseProperties(PlayerTransformer player, long total, String score) {
            this.player = player;
            this.total = total;
            this.score = score;
        }

        public PlayerTransformer getPlayer() {
            return player;
        }

        long getTotal() {
            return total;
        }

        String getScore() {
            return score;
        }
    }
}
