# <img src="./logo.svg"> InstantJPDict

InstantJPDict is an Android application which provides instant Japanese-to-English dictionary lookups using on-device OCR (Optical Character Recognition). It allows users to capture text from their screen and get immediate definitions, making it ideal for reading games, browsing social media, or any other activity where copying text is difficult.

Currently only tested with [JMDict and KANJIDIC for Yomitan](https://github.com/yomidevs/jmdict-yomitan).

## Demo
<div><video controls src="https://github.com/user-attachments/assets/06a01786-9082-4fea-a1fb-0a463180bd99"></video></div>

## Features
- **On-device OCR**: High-speed Japanese text recognition without needing an internet connection.
- **Instant Lookup**: Tap recognized characters to see dictionary entries immediately.
- **Deinflection**: Support for verb and adjective conjugations, just like yomitan.
- **Floating Overlay**: Accessible from any app via an accessibility service.
- **Frictionless Corrections**: In the rare case the OCR makes a mistake, corrections are only a tap away. We use the text recognition model's own prediction ratings to provide the most likely alternatives, as well as a manual input mode.
- **Yomitan Dictionaries**: Ingests Yomitan format dictionaries such as https://github.com/yomidevs/jmdict-yomitan

## Roadmap
- [ ] Train a better model for vertical text recognition
- [ ] Camera mode
- [ ] Train a spline-based text line detection model for good camera OCR and weird text

## Credits
This project utilizes the excellent OCR models from the **MeikiOCR** project:
- [MeikiOCR Repository](https://github.com/rtr46/meikiocr)
- [meiki.text.detect.v0 (Hugging Face)](https://huggingface.co/rtr46/meiki.text.detect.v0)
- [meiki.txt.recognition.v0 (Hugging Face)](https://huggingface.co/rtr46/meiki.txt.recognition.v0)

This project was also heavily inspired by the **Yomitan** hover dictionary, uses its rule files,
and ingests its dictionary format:
- [Yomitan Repository](https://github.com/yomidevs/yomitan)


## License
This project is licensed under the AGPL-v3 License. See the [LICENSE](LICENSE) file for details.
If you would like to use this code under a different license, please contact me. As this is a
learning tool I developed solely for myself and my friends, I would like to keep the project as free
and open as possible for individuals to use and hack on.
