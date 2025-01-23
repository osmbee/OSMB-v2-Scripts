package com.osmb.script.agility.ui.javafx;

import com.osmb.script.agility.AIOAgility;
import com.osmb.script.agility.Course;
import com.osmb.script.agility.courses.alkharid.AlKharid;
import com.osmb.script.agility.courses.canafis.Canafis;
import com.osmb.script.agility.courses.draynor.Draynor;
import com.osmb.script.agility.courses.falador.Falador;
import com.osmb.script.agility.courses.gnome.GnomeStronghold;
import com.osmb.script.agility.courses.varrock.Varrock;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.StringConverter;

public class Controller {

    @FXML
    private ComboBox<Course> courseCombobox;

    @FXML
    private TextField eatHighField;

    @FXML
    private TextField eatLowField;
    @FXML
    private TextField foodIdField;
    @FXML
    private Button startButton;
    private Course selectedCourse;

    public TextField getFoodIdField() {
        return foodIdField;
    }

    public ComboBox<Course> getCourseCombobox() {
        return courseCombobox;
    }

    public TextField getEatHighField() {
        return eatHighField;
    }

    public TextField getEatLowField() {
        return eatLowField;
    }

    public Button getStartButton() {
        return startButton;
    }

    public Course selectedCourse() {
        return selectedCourse;
    }

    public void init() {
        courseCombobox.setPromptText("Select Course");
        courseCombobox.setItems(FXCollections.observableArrayList(new GnomeStronghold(), new Draynor(), new AlKharid(), new Varrock(), new Canafis(), new Falador()));

        courseCombobox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Course course) {
                return course != null ? course.name() : "";
            }

            @Override
            public Course fromString(String string) {
                return courseCombobox.getItems().stream().filter(course -> course.name().equals(string)).findFirst().orElse(null);
            }
        });
        startButton.setOnAction(actionEvent -> {
            Course selectedCourse = courseCombobox.getValue();
            if (selectedCourse != null) {
                this.selectedCourse = selectedCourse;
                //stage.close();
                ((Stage) startButton.getScene().getWindow()).close();

            }
        });
        eatLowField.setText(Integer.toString(AIOAgility.DEFAULT_EAT_LOW));
        eatHighField.setText(Integer.toString(AIOAgility.DEFAULT_EAT_HIGH));
    }

}
