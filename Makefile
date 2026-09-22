SHELL := /bin/bash
.ONESHELL:
.SHELLFLAGS := -eu -o pipefail -c

# AGP rejects JDK 26; 17-21 only.
JAVA_HOME ?= /usr/lib/jvm/java-21-temurin

BUILD_DIR := _build
ARTIFACT  := projectivity-jellyfin-wallpaper
VERSIONS  := gradle/libs.versions.toml

.PHONY: release build clean

release:
	last=$$(git tag -l 'v1.*' --sort=-v:refname | head -n1)
	if [[ -z "$$last" ]]; then
		tag=v1.0
	else
		tag="v1.$$(( $${last#v1.} + 1 ))"
	fi
	code=$$(( $$(sed -n 's/^versionCode = "\(.*\)"/\1/p' $(VERSIONS)) + 1 ))
	sed -i "s/^versionCode = .*/versionCode = \"$$code\"/" $(VERSIONS)
	sed -i "s/^versionName = .*/versionName = \"$${tag#v}\"/" $(VERSIONS)
	if [[ -n "$$(git status --porcelain)" ]]; then
		git add -A
		git commit -m "Release $$tag"
	fi
	git tag -a "$$tag" -m "Release $$tag"
	JAVA_HOME=$(JAVA_HOME) ./gradlew :app:assembleRelease
	mkdir -p $(BUILD_DIR)
	cp app/build/outputs/apk/release/app-release.apk "$(BUILD_DIR)/$(ARTIFACT)-$$tag.apk"
	echo "-> $(BUILD_DIR)/$(ARTIFACT)-$$tag.apk"

build:
	JAVA_HOME=$(JAVA_HOME) ./gradlew :app:assembleDebug

clean:
	JAVA_HOME=$(JAVA_HOME) ./gradlew clean
	rm -rf $(BUILD_DIR)
