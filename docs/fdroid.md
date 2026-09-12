# The F-Droid repository is retired

The project used to publish its own F-Droid repository, so a panel could install and update ha-paneld on its own with no computer. That repository is no longer updated.

It was retired because it only ever installed the app. It could not connect the panel to Home Assistant, so every panel installed this way still needed setting up another way. The reason it existed, reaching a panel without a computer, is now served by Home Assistant.

## What to use instead

Install [Panel Assistant](https://panel-assistant.io) in Home Assistant and give it the panel's address. Home Assistant installs ha-paneld over the network, hands over to the panel's own setup, and connects the panel once that is done, so there is still no computer needed at the panel. Where the panel cannot be reached over the network, Panel Assistant can install over USB from a browser instead.

See [Install](../README.md#install) for both paths.

## If you already added the repository

Nothing on your panel stops working, and the versions already published remain available. You will not be offered new versions from it, so remove it from the F-Droid client and update the panel from Home Assistant instead.
