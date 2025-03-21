package com.osmb.script.wintertodt;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class PNGPixelReader {

    public static void main(String[] args) {
        System.out.println(new Color(210,210,210).getRGB());
        // Path to the folder containing PNG images
        String folderPath = "C:\\Users\\joeta\\Desktop\\dots";

        // Create a File object for the folder
        File folder = new File(folderPath);

        // Check if the folder exists and is a directory
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("The specified folder does not exist or is not a directory.");
            return;
        }

        // Get all files in the folder
        File[] files = folder.listFiles();

        // Iterate through each file in the folder
        if (files != null) {
            for (File file : files) {
                // Check if the file is a PNG image
                if (file.isFile() && file.getName().toLowerCase().endsWith(".png")) {
                    System.out.println("Processing file: " + file.getName());

                    try {
                        // Read the PNG image
                        BufferedImage image = ImageIO.read(file);

                        // Get the image dimensions
                        int width = image.getWidth();
                        int height = image.getHeight();

                        // Print pixel data
                        System.out.println("Image dimensions: " + width + "x" + height);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                // Get the RGB value of the pixel at (x, y)
                                int pixel = image.getRGB(x, y);

                                // Extract the red, green, blue, and alpha components
                                int alpha = (pixel >> 24) & 0xff;
                                int red = (pixel >> 16) & 0xff;
                                int green = (pixel >> 8) & 0xff;
                                int blue = pixel & 0xff;

                                // Print the pixel data
                                System.out.printf("%d,",
                                        pixel);
                            }
                        }
                    } catch (IOException e) {
                        System.out.println("Error reading file: " + file.getName());
                        e.printStackTrace();
                    }
                }
            }
        } else {
            System.out.println("The folder is empty or an error occurred while listing files.");
        }
    }
}