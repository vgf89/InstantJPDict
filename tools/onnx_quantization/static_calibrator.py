import os
import numpy as np
from PIL import Image
from onnxruntime.quantization import CalibrationDataReader
import onnxruntime as ort

class StaticCalibrator(CalibrationDataReader):
    """
    Simple static‑calibration helper that mimics the `DataReader`
    used in the ailia‑ai example.  It:
      * walks a directory of images,
      * loads each image with Pillow,
      * resizes it to the model's required input size,
      * converts it to a NumPy array of the correct dtype,
      * yields a dict {input_name: np_array} for the quantizer.
    """

    def __init__(self, dataset_dir: str, model_path: str):
        """
        :param dataset_dir: Path to a folder containing calibration images.
        :param model_path: Path to the *fp32* ONNX model (used only to read input shape).
        """
        self.dataset_dir = dataset_dir
        self.model_path = model_path

        # Load the model once to discover the input name & shape.
        sess = ort.InferenceSession(self.model_path)
        self.input_name = sess.get_inputs()[0].name
        # Expected shape is (N, C, H, W) – we only need H and W.
        _, self.channels, self.height, self.width = sess.get_inputs()[0].shape
        sess = None

        # Build a list of image file paths (any common image extension)
        self.image_paths = [
            os.path.join(root, f)
            for root, _dirs, files in os.walk(self.dataset_dir)
            for f in files
            if f.lower().endswith(('.png', '.jpg', '.jpeg', '.bmp', '.webp'))
        ]

        if not self.image_paths:
            raise RuntimeError(f"No images found in calibration folder: {self.dataset_dir}")

        self._iter = iter(self)

    def __len__(self):
        return len(self.image_paths)

    def get_next(self):
        """
        Returns the next batch of data, or None if there are no more batches.
        """
        try:
            return next(self._iter)
        except StopIteration:
            return None

    def set_range(self, range_val):
        """
        Required by CalibrationDataReader interface.
        """
        pass

    def __iter__(self):
        """
        Yields a single‑element dict that matches the model's input signature.
        """
        for img_path in self.image_paths:
            # Load, convert to RGB (or keep as is if channels==1), resize, and normalize.
            img = Image.open(img_path).convert('RGB' if self.channels == 3 else 'L')
            img = img.resize((self.width, self.height), Image.BILINEAR)

            # Convert to float32 in [0,1] – the original repo uses float32.
            img_np = np.asarray(img, dtype=np.float32) / 255.0

            # Add batch dimension and channel order (N, C, H, W)
            if self.channels == 3:
                # PIL gives (H, W, C) → transpose to (C, H, W)
                img_np = np.transpose(img_np, (2, 0, 1))
            else:  # single channel
                img_np = np.expand_dims(img_np, axis=0)  # (1, H, W)

            img_np = np.expand_dims(img_np, axis=0)   # (1, C, H, W)

            # Provide both required inputs: the image tensor and original size (using the resized dimensions)
            orig_size = np.array([[self.height, self.width]], dtype=np.int64)
            yield {self.input_name: img_np, 'orig_target_sizes': orig_size}
