mvn vaadin:dance clean package
TARGET=/etc/systemd/jar/stern-home-leaderboards.jar
sudo cp target/stern-home-leaderboards-1.0-SNAPSHOT.jar $TARGET
sudo chmod 755 $TARGET
sudo service stern-home-leaderboards restart
