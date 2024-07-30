package com.arun.gmapspoints;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.maps.android.SphericalUtil;  // Import SphericalUtil

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 2;
    private static final int OPEN_FILE_REQUEST_CODE = 3;
    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private List<LatLng> polygonPoints = new ArrayList<>();
    private Polygon polygon;
    private TextView areaTextView;
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        areaTextView = findViewById(R.id.tv_area);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map_container);
        mapFragment.getMapAsync(this);

        findViewById(R.id.fab_open_directory).setOnClickListener(view -> openFile());

        findViewById(R.id.fab_refresh).setOnClickListener(view -> refreshMap());

        findViewById(R.id.fab_save).setOnClickListener(view -> {
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_REQUEST_CODE);
            } else {
                saveMarkers();
            }
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        updateLocationUI();
        getDeviceLocation();
        mMap.setOnMapClickListener(latLng -> {
            mMap.addMarker(new MarkerOptions().position(latLng));
            polygonPoints.add(latLng);
            updatePolygon();
        });
    }

    private void updateLocationUI() {
        if (mMap == null) {
            return;
        }
        try {
            mMap.setMyLocationEnabled(true);
        } catch (SecurityException e) {
            Log.e(TAG, "Exception: %s", e);
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show();
        }
    }

    private void getDeviceLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }
        fusedLocationClient.getLastLocation()
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Location location = task.getResult();
                        LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 20));
                    } else {
                        Log.d(TAG, "Current location is null. Using defaults.");
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(0, 0), 15));
                        Toast.makeText(this, "Current location is null", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void updatePolygon() {
        if (polygon != null) {
            polygon.remove();
        }
        if (polygonPoints.size() > 1) {
            PolygonOptions polygonOptions = new PolygonOptions()
                    .addAll(polygonPoints)
                    .strokeColor(Color.BLACK)
                    .fillColor(0x30ff0000);
            polygon = mMap.addPolygon(polygonOptions);
            double area = calculatePolygonArea(polygonPoints);
            double areaInHectares = area / 10000; // 1 hectare = 10,000 square meters
            areaTextView.setText(String.format(Locale.getDefault(), "Area: %.2f sq m (%.2f ha)", area, areaInHectares));
            Toast.makeText(this, "Area updated", Toast.LENGTH_SHORT).show();
        } else {
            areaTextView.setText("Area: 0.0 sq m (0.0 ha)");
            Toast.makeText(this, "Add more points to calculate area", Toast.LENGTH_SHORT).show();
        }
    }

    private double calculatePolygonArea(List<LatLng> latLngs) {
        if (latLngs.size() < 3) {
            return 0.0;
        }
        // Use SphericalUtil to calculate the area of the polygon
        return SphericalUtil.computeArea(latLngs);
    }

    private void refreshMap() {
        polygonPoints.clear();
        updatePolygon();
        mMap.clear();
        Toast.makeText(this, "Map refreshed", Toast.LENGTH_SHORT).show();
    }

    private void saveMarkers() {
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < polygonPoints.size(); i++) {
            LatLng point = polygonPoints.get(i);
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("point_number", i + 1);
                jsonObject.put("latitude", point.latitude);
                jsonObject.put("longitude", point.longitude);
                jsonArray.put(jsonObject);
            } catch (JSONException e) {
                Log.e(TAG, "Error adding point to JSON: " + e.getMessage());
                Toast.makeText(this, "Error adding point to JSON", Toast.LENGTH_SHORT).show();
            }
        }
        JSONObject finalJson = new JSONObject();
        try {
            finalJson.put("points", jsonArray);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating final JSON: " + e.getMessage());
            Toast.makeText(this, "Error creating final JSON", Toast.LENGTH_SHORT).show();
        }

        // Save to the app's specific external storage directory
        File dir = getExternalFilesDir(null); // Gets the directory /storage/emulated/0/Android/data/com.arun.gmapspoints/files/
        String fileName = "farm" + getNextFileNumber() + ".json";
        File file = new File(dir, fileName);
        try (FileWriter fileWriter = new FileWriter(file)) {
            fileWriter.write(finalJson.toString());
            Toast.makeText(this, "Data saved to " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Error saving JSON file: " + e.getMessage());
            Toast.makeText(this, "Error saving JSON file", Toast.LENGTH_SHORT).show();
        }
    }

    private int getNextFileNumber() {
        File dir = getExternalFilesDir(null); // Gets the directory /storage/emulated/0/Android/data/com.arun.gmapspoints/files/
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                int maxNumber = 0;
                for (File file : files) {
                    String name = file.getName();
                    if (name.startsWith("farm") && name.endsWith(".json")) {
                        try {
                            int number = Integer.parseInt(name.replace("farm", "").replace(".json", ""));
                            maxNumber = Math.max(maxNumber, number);
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "Error parsing file number: " + e.getMessage());
                        }
                    }
                }
                return maxNumber + 1;
            }
        }
        return 1;
    }

    private void openFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, OPEN_FILE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_FILE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    if (inputStream != null) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                        StringBuilder builder = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            builder.append(line);
                        }
                        reader.close();
                        parseJsonData(builder.toString());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading JSON file: " + e.getMessage());
                    Toast.makeText(this, "Error reading JSON file", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void parseJsonData(String jsonData) {
        try {
            JSONObject jsonObject = new JSONObject(jsonData);
            JSONArray pointsArray = jsonObject.getJSONArray("points");

            // Clear existing markers and polygon
            mMap.clear();
            polygonPoints.clear();

            for (int i = 0; i < pointsArray.length(); i++) {
                JSONObject pointObject = pointsArray.getJSONObject(i);
                double latitude = pointObject.getDouble("latitude");
                double longitude = pointObject.getDouble("longitude");
                LatLng point = new LatLng(latitude, longitude);
                polygonPoints.add(point);
                // Add markers for each point
                mMap.addMarker(new MarkerOptions().position(point));
            }

            updatePolygon();
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON data: " + e.getMessage());
            Toast.makeText(this, "Error parsing JSON data", Toast.LENGTH_SHORT).show();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateLocationUI();
                getDeviceLocation();
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveMarkers();
            } else {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
