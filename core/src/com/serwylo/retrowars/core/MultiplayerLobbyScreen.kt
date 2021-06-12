package com.serwylo.retrowars.core

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.actions.Actions.*
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.serwylo.beatgame.ui.*
import com.serwylo.retrowars.RetrowarsGame
import com.serwylo.retrowars.UiAssets
import com.serwylo.retrowars.net.Network
import com.serwylo.retrowars.net.Player
import com.serwylo.retrowars.net.RetrowarsClient
import com.serwylo.retrowars.net.RetrowarsServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.IOException

class MultiplayerLobbyScreen(game: RetrowarsGame): Scene2dScreen(game, {
    close()
    game.showMainMenu()
}) {

    companion object {
        const val TAG = "MultiplayerLobby"

        private fun close() {
            // TODO: Move to coroutine and show status to user...
            RetrowarsClient.get()?.listen(
                // Don't do anything upon network close, because we know we are about to shut down our
                // own server.
                networkCloseListener = {}
            )

            RetrowarsServer.stop()
            RetrowarsClient.disconnect()
        }
    }

    private val wrapper = Table()

    /**
     * Keep track of this, so that when we see a new player for the first time,
     * they can be introduced with a little jump.
     */
    private val previouslyRenderedPlayers = mutableSetOf<Player>()

    private val styles = game.uiAssets.getStyles()
    private val strings = game.uiAssets.getStrings()

    init {
        // If returning from the end of one game after hitting "play again", then we should go
        // straight to the correct screen with our existing client/server details
        val client = RetrowarsClient.get()
        val server = RetrowarsServer.get()

        // TODO: This should go into the else part of the below checks so we don't build more UI than neccesary.
        Gdx.app.log(TAG, "Showing main lobby with client $client and server $server")
        showMainLobby()

        if (server != null) {
            // TODO: But don't allow 'start' yet because all players are not yet ready... Some may be playing, some may not yet be back in the lobby yet.
            Gdx.app.log(TAG, "Returning to the lobby with an active server connection.")

            if (client == null) {
                // TODO: Something went pretty bad here, we should either kill the server and start again,
                //       or join the server again.
                Gdx.app.error(TAG, "Returned to lobby after a game, but no active client connection to go with our active server one.")
            } else {

                Gdx.app.log(TAG, "Returned to the lobby after a game, and we have an active client $client and active server $server")
                listenToClient(client)

                Gdx.app.log(TAG, "Finished listening to client, will now show server lobby with client $client and server $server")
                showServerLobby(client, server)
            }
        } else if (client != null) {
            Gdx.app.log(TAG, "Returning to the lobby with an active client connection.")
            listenToClient(client)
            showClientLobby(client)
        }
    }

    private fun showMainLobby() {

        val table = Table().apply {
            setFillParent(true)
            pad(UI_SPACE)

            val heading = makeHeading(strings["multiplayer-lobby.title"], styles, strings) {
                GlobalScope.launch {
                    Gdx.app.log(TAG, "Returning from lobby to main screen. Will close of anny server and/or client connection.")
                    showStatus("Disconnecting...")
                    close()
                    game.showMainMenu()
                }
            }

            add(heading).center()

            row()
            add(wrapper).expand()

            wrapper.apply {

                val description = Label("Play with others\non the same local network", game.uiAssets.getStyles().label.medium)
                description.setAlignment(Align.center)

                add(description).colspan(2).spaceBottom(UI_SPACE)
                row()

                add(makeButton("Start Server", styles) {
                    startServer()
                }).right()

                add(makeButton("Join Server", styles) {
                    joinServer()
                }).left()
            }

        }

        stage.addActor(table)

    }

    private fun startServer() {

        GlobalScope.launch(Dispatchers.IO) {

            try {
                Gdx.app.log(TAG, "Starting a new multiplayer server.")
                showStatus("Starting server...")
                val server = RetrowarsServer.start()

                Gdx.app.log(TAG, "Server started. Now connecting as a client.")
                showStatus("Connecting...")
                val client = createClient(true)

                Gdx.app.log(TAG, "Client connected. Will now show the server for client $client and server $server")
                showServerLobby(client, server)
            } catch (e: IOException) {
                showStatus("Error starting a server.\nIs port ${Network.defaultPort} or ${Network.defaultUdpPort} in use?")
            }

        }

    }

    private fun showStatus(message: String) {
        wrapper.clear()
        wrapper.add(Label(message, styles.label.medium))
    }

    private fun createClient(isAlsoServer: Boolean): RetrowarsClient {
        val client = RetrowarsClient.connect(isAlsoServer)
        listenToClient(client)
        return client
    }

    // TODO: Race condition between this and the other client.listen(...) call (after appending avatars)
    //       seems to cause a condition where you just see "Connected to server" but no information
    //       about any of the players.
    private fun listenToClient(client: RetrowarsClient) {
        Gdx.app.log(TAG, "Listening to just start game or network close listener from client.")
        client.listen(
            startGameListener = { initiateStartCountdown() },
            networkCloseListener = { wasGraceful -> game.showNetworkError(game, wasGraceful) },
            playersChangedListener = { showClientLobby(client) }
        )
    }

    private fun initiateStartCountdown() {
        wrapper.apply {
            clear()

            var count = 5

            // After some experimentation, it seems the only way to get this label to animate from
            // within a table is to wrap it in a Container with isTransform = true (and don't forget
            // to enable GL_BLEND somewhere for the alpha transitions).
            val countdown = Label(count.toString(), game.uiAssets.getStyles().label.huge)
            val countdownContainer = Container(countdown).apply { isTransform = true }

            row()
            add(countdownContainer).center().expand()

            countdownContainer.addAction(
                sequence(

                    repeat(count,
                        parallel(
                            Actions.run {
                                countdown.setText((count).toString())
                                count --
                            },
                            sequence(
                                alpha(0f, 0f), // Start at 0f alpha (hence duration 0f)...
                                alpha(1f, 0.4f) // ... and animate to 1.0f quite quickly.
                            ),
                            sequence(
                                scaleTo(3f, 3f, 0f), // Start at 3x size (hence duration 0f)...
                                scaleTo(1f, 1f, 0.75f) // ... and scale back to normal size.
                            ),
                            delay(1f) // The other actions finish before the full second is up. Therefore ensure we show the counter for a full second before continuing.
                        )
                    ),

                    Actions.run {
                        Gdx.app.postRunnable {
                            val gameDetails = RetrowarsClient.get()?.me()?.getGameDetails()
                            if (gameDetails == null) {
                                // TODO: Handle this better
                                Gdx.app.log(TAG, "Unable to figure out which game to start.")
                                game.showMainMenu()
                            } else {
                                game.startGame(gameDetails.createScreen(game, gameDetails))
                            }
                        }
                    }
                )
            )

        }

    }

    private fun joinServer() {

        GlobalScope.launch(Dispatchers.IO) {

            Gdx.app.log(TAG, "Connecting to server.")
            showStatus("Searching for server\non the local network...")
            try {
                val client = createClient(false)

                Gdx.app.log(TAG, "Connected")
                showClientLobby(client)
            } catch (e: IOException) {
                showStatus("Could not find a server on the local network.")
            }

        }

    }

    private fun showClientLobby(client: RetrowarsClient) {
        wrapper.apply {
            clear()

            row()
            add(Label("Connected to server", styles.label.large))

            Gdx.app.log(TAG, "Showing client lobby. Connected to server. Will now show avatars for client $client")
            appendAvatars(this, client)
        }
    }

    private fun appendAvatars(table: Table, client: RetrowarsClient, server: RetrowarsServer? = null) {

        Gdx.app.log(TAG, "Appending avatars for client $client and server $server")

        val infoLabel = Label("", game.uiAssets.getStyles().label.medium)

        val startButton = if (server == null) null else makeLargeButton("Start Game", game.uiAssets.getStyles()) {
            server.startGame()
        }

        val cpuButton = if (server == null) null else makeButton("Add CPU Player", game.uiAssets.getStyles()) {
            server.addCpuPlayer()
        }

        table.row()
        table.add(infoLabel)

        if (startButton != null) {
            table.row()
            table.add(startButton).spaceTop(UI_SPACE)
        }

        if (cpuButton != null) {
            table.row()
            table.add(cpuButton).spaceTop(UI_SPACE)
        }

        table.row()
        val avatarCell = table.add().expandY()

        val renderPlayers = { toShow: List<Player> ->

            Gdx.app.log(TAG, "Showing list of ${toShow.size} players...")

            avatarCell.clearActor()
            avatarCell.setActor<Actor>(makeAvatarTiles(toShow, game.uiAssets))

            if (server != null) {
                when {
                    toShow.size <= 1 -> {
                        infoLabel.setText("Waiting for others to join...")
                        startButton?.isDisabled = true
                        startButton?.touchable = Touchable.disabled
                    }
                    toShow.any { it.status != Player.Status.lobby } -> {
                        infoLabel.setText("Waiting for all players to return to the lobby...")
                        startButton?.isDisabled = true
                        startButton?.touchable = Touchable.disabled
                    }
                    else -> {
                        infoLabel.setText("Ready to play!")
                        startButton?.isDisabled = false
                        startButton?.touchable = Touchable.enabled
                    }
                }
            } else {
                infoLabel.setText("Waiting for server to begin game...")
            }

            // TODO: table.invalidate() so the function just depends on its input??
            wrapper.invalidate()
        }

        // If returning to a game, we already have a list of players.
        // If it is a new game, we will have zero (not even ourselves) and will need to
        // rely on the playersChangedListener below.
        val originalPlayers: List<Player> = client.players
        if (originalPlayers.isNotEmpty()) {
            Gdx.app.log(TAG, "Showing list of existing clients.")
            renderPlayers(originalPlayers)
        }

        Gdx.app.log(TAG, "Listening to all events from client about starting game, new players, etc...")
        client.listen(
            startGameListener = { initiateStartCountdown() },
            networkCloseListener = { wasGraceful -> game.showNetworkError(game, wasGraceful) },
            playersChangedListener = { updatedPlayers -> renderPlayers(updatedPlayers) },
            playerStatusChangedListener = { _, _ -> renderPlayers(client.players) }
        )

    }

    private fun makeAvatarTiles(players: List<Player>, uiAssets: UiAssets) = Table().apply {

        pad(UI_SPACE)

        row().space(UI_SPACE).pad(UI_SPACE)

        val myAvatar = Avatar(players[0], uiAssets)
        if (!previouslyRenderedPlayers.contains(players[0])) {
            myAvatar.addAction(CustomActions.bounce())
        }
        add(myAvatar)
        add(makeGameIcon(players[0].getGameDetails(), uiAssets))
        add(Label("You", uiAssets.getStyles().label.large))

        if (players.size > 1) {

            players.subList(1, players.size).forEach { player ->

                row().space(UI_SPACE).pad(UI_SPACE)

                val avatar = Avatar(player, uiAssets)

                if (!previouslyRenderedPlayers.contains(player)) {
                    avatar.addAction(CustomActions.bounce())
                }

                add(avatar)
                add(makeGameIcon(player.getGameDetails(), uiAssets))

                add(
                    Label(
                        when(player.status) {
                            Player.Status.playing -> "Playing"
                            Player.Status.dead -> "Dead"
                            Player.Status.lobby -> "Ready"
                            else -> "?"
                        },
                        uiAssets.getStyles().label.medium
                    )
                )

            }
        }

        previouslyRenderedPlayers.clear()
        previouslyRenderedPlayers.addAll(players)

    }

    private fun showServerLobby(client: RetrowarsClient, server: RetrowarsServer) {
        wrapper.apply {
            clear()

            row()
            add(Label("Server started", styles.label.large))

            Gdx.app.log(TAG, "Showing server lobby. Server started. Will now show avatars for client $client and server $server")

            appendAvatars(this, client, server)
        }
    }

    override fun dispose() {
        super.dispose()

        Gdx.app.log(TAG, "Disposing multiplayer lobby, will ask stage to dispose itself.")
        stage.dispose()
    }
}
