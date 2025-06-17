package com.project.ui;

import javafx.geometry.Bounds;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.control.Label;
import javafx.geometry.Pos;
import javafx.scene.effect.DropShadow;

public class CursorMarker extends StackPane {
    private final Line cursorLine;
    private final Label label;
    private final Color cursorColor;

    public CursorMarker(String username, Color color) {
        super();
        this.cursorColor = color;

        cursorLine = new Line(0, 0, 0, 20);
        cursorLine.setStroke(color);
        cursorLine.setStrokeWidth(2.5);

        label = new Label(username);
        label.setTextFill(Color.WHITE);
        label.setStyle("-fx-background-color: " + toRGBCode(color) + "; -fx-padding: 2 5 2 5; -fx-background-radius: 3;");

        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.color(0, 0, 0, 0.5));
        shadow.setRadius(3);
        label.setEffect(shadow);

        setAlignment(Pos.TOP_LEFT);
        label.setTranslateY(-20);

        getChildren().addAll(cursorLine, label);
        setMouseTransparent(true);
        setVisible(false);
    }

    public void updatePosition(Bounds caretBounds) {
        if (caretBounds != null) {
            setLayoutX(caretBounds.getMinX());
            setLayoutY(caretBounds.getMinY());

            cursorLine.setEndY(caretBounds.getHeight());

            setVisible(true);
            toFront();
        } else {
            setVisible(false);
        }
    }

    public void setUsername(String username) {
        label.setText(username);
    }

    private String toRGBCode(Color color) {
        return String.format("#%02X%02X%02X",
            (int)(color.getRed() * 255),
            (int)(color.getGreen() * 255),
            (int)(color.getBlue() * 255));
    }
}