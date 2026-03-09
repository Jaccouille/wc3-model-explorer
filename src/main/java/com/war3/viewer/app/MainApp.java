package com.war3.viewer.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import javax.imageio.ImageIO;

public class MainApp extends Application {
    @Override
    public void start(final Stage stage) {
        // Ensure all ImageIO plugins (including BLP) are registered before any texture loading.
        ImageIO.scanForPlugins();
        AssetBrowserPane root = new AssetBrowserPane(stage);
        Scene scene = new Scene(root, 1520, 920);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());

        stage.setTitle("War3 Advanced Model Viewer");
        stage.setScene(scene);
        stage.setMinWidth(1080);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(event -> root.shutdown());
        stage.show();
    }

    public static void main(final String[] args) {
        launch(args);
    }
}
