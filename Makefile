PREFIX ?= $(HOME)/.local
BIN := $(PREFIX)/bin
PYTHON := /usr/bin/python3
CLT := /Library/Developer/CommandLineTools
SWIFTC := swiftc -swift-version 5
HELPER := mac/Tossling/*.swift
FINDER := -parse-as-library -application-extension -module-name TosslingFinder mac/TosslingFinder/FinderSync.swift

.PHONY: install uninstall check selftest test build app release clean

install:
	@mkdir -p "$(BIN)"
	@ln -sfn "$(CURDIR)/bin/tossling" "$(BIN)/tossling"
	@ln -sfn "$(CURDIR)/bin/tossling" "$(BIN)/tossy"
	@echo "tossling -> $(BIN)/tossling (tossy too)"

uninstall:
	@rm -f "$(BIN)/tossling" "$(BIN)/tossy"
	@echo "tossling удалён из $(BIN)"

check:
	@bash -n bin/tossling
	@for f in cli/*.py cli/lib/*.py tests/*.py; do $(PYTHON) -m py_compile "$$f" || exit 1; done
	@$(SWIFTC) -typecheck $(HELPER)
	@$(SWIFTC) -typecheck $(FINDER)
	@[ ! -x $(CLT)/usr/bin/swiftc ] || DEVELOPER_DIR=$(CLT) $(SWIFTC) -typecheck $(HELPER)
	@[ ! -x $(CLT)/usr/bin/swiftc ] || DEVELOPER_DIR=$(CLT) $(SWIFTC) -typecheck $(FINDER)

selftest:
	@mkdir -p build
	@$(SWIFTC) $(HELPER) -o build/tossling-selftest
	@build/tossling-selftest --selftest mac/Tossling/vectors.json >/dev/null

test: check selftest
	@$(PYTHON) -m unittest discover -s tests -q

build:
	@cd cli/lib && $(PYTHON) -c 'import sys, helper, finder_ext; out = sys.argv[1]; helper.Helper(out).build(); finder_ext.build(icon=out + "/Tossling.app/Contents/Resources/AppIcon.icns", dest=out + "/Tossling Finder.app")' "$(CURDIR)/build"

app:
	@$(PYTHON) scripts/build_app.py

release:
	@$(PYTHON) scripts/build_app.py --release

clean:
	@rm -rf build
