#!/bin/bash

echo "Starting Collaborative Text Editor..."
echo

# Check if Maven is installed
if ! command -v mvn &> /dev/null
then
    echo "Maven not found. Please install Maven and add it to your PATH."
    exit 1
fi

# Check if Java is installed
if ! command -v java &> /dev/null
then
    echo "Java not found. Please install JDK 17 or higher and add it to your PATH."
    exit 1
fi

# Build and run the application
echo "Building the application..."
mvn clean package

if [ $? -ne 0 ]; then
    echo "Failed to build the application."
    exit 1
fi

echo
echo "Running the application..."
mvn javafx:run 