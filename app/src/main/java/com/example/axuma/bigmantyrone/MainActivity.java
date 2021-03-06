package com.example.axuma.bigmantyrone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;


import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONArray;
import org.w3c.dom.Text;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity
        implements OnMapReadyCallback, View.OnClickListener {

    private static final int MAXPOSITIONS = 50;
    private static final String PREFERENCEID = "Credentials";

    private String username, password;
    private String[] positions = new String[MAXPOSITIONS];
    private Double[] bmtlat = new Double[MAXPOSITIONS];    //bmt approved
    private Double[] bmtlong = new Double[MAXPOSITIONS]; //bmt approved insert
    private Double[] bmtdist = new Double[MAXPOSITIONS - 1]; //bmt approved
    private Date[] bmttime = new Date[MAXPOSITIONS]; //bmt approved
    //private ArrayAdapter<String> myAdapter;
    private Double totaldist = 0d;
    private Double finaldist = 0d;

    private Button myButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Retrieve the content view that renders the map.
        setContentView(R.layout.activity_main);

        // initialize the array so that every position has an object (even it is empty string)
        for (int i = 0; i < positions.length; i++)
            positions[i] = "";

        // BMT- initialize new array with just long and lat so every position has object (justincase)
        for (int i = 0; i < bmtlat.length; i++)
            bmtlat[i] = 0d;
        for (int i = 0; i < bmtlong.length; i++)
            bmtlong[i] = 0d;
        for (int i = 0; i < bmtdist.length; i++)
            bmtdist[i] = 0d;
        for (int i = 0; i < bmttime.length; i++)
            bmttime[i] = null;


        // setup the adapter for the array
        //myAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, positions);

        // then connect it to the list in application's layout
        //ListView listView = (ListView) findViewById(R.id.mylist);
        //listView.setAdapter(myAdapter);

        // setup the button event listener to receive onClick events
        myButton = (Button) findViewById(R.id.mybutton); //fetch button
        myButton.setOnClickListener(this);
        ((Button) findViewById(R.id.ButtonDraw)).setOnClickListener(this); //map button

        // check that we know username and password for the Thingsee cloud
        SharedPreferences prefGet = getSharedPreferences(PREFERENCEID, Activity.MODE_PRIVATE);
        username = prefGet.getString("username", "");
        password = prefGet.getString("password", "");

        //check if button is correct one to avoid query popping up when pressing other button
        if ((username.length() == 0 || password.length() == 0) && myButton.isPressed())
            // no, ask them from the user
            queryDialog(this, getResources().getString(R.string.prompt));
    }

    private void queryDialog(Context context, String msg) {
        // get prompts.xml view
        LayoutInflater li = LayoutInflater.from(context);
        View promptsView = li.inflate(R.layout.credentials_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);

        // set prompts.xml to alertdialog builder
        alertDialogBuilder.setView(promptsView);

        final TextView dialogMsg = (TextView) promptsView.findViewById(R.id.textViewDialogMsg);
        final EditText dialogUsername = (EditText) promptsView.findViewById(R.id.editTextDialogUsername);
        final EditText dialogPassword = (EditText) promptsView.findViewById(R.id.editTextDialogPassword);

        dialogMsg.setText(msg);
        dialogUsername.setText(username);
        dialogPassword.setText(password);

        // set dialog message
        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                // get user input and set it to result
                                username = dialogUsername.getText().toString();
                                password = dialogPassword.getText().toString();

                                SharedPreferences prefPut = getSharedPreferences(PREFERENCEID, Activity.MODE_PRIVATE);
                                SharedPreferences.Editor prefEditor = prefPut.edit();
                                prefEditor.putString("username", username);
                                prefEditor.putString("password", password);
                                prefEditor.commit();
                            }
                        })
                .setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        });

        // create alert dialog
        AlertDialog alertDialog = alertDialogBuilder.create();

        // show it
        alertDialog.show();
    }

    public void onClick(View v) {
        switch (v.getId()) {

            case R.id.ButtonDraw: //map button
                // Get the SupportMapFragment and request notification
                // when the map is ready to be used.
                SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.map);
                mapFragment.getMapAsync(this);
                break;

            case R.id.mybutton: //fetch button
                Log.d("USR", "Button pressed");

                // we make the request to the Thingsee cloud server in backgroud
                // (AsyncTask) so that we don't block the UI (to prevent ANR state, Android Not Responding)
                new MainActivity.TalkToThingsee().execute("QueryState");

                if (totaldist != 0d){ //set totaldist to be zero at every press so calculation is accurate
                    totaldist = 0d;
                }
        }
    }

    /* This class communicates with the ThingSee client on a separate thread (background processing)
     * so that it does not slow down the user interface (UI)
     */
    private class TalkToThingsee extends AsyncTask<String, Integer, String> {
        ThingSee thingsee;
        List<Location> coordinates = new ArrayList<Location>();

        @Override
        protected String doInBackground(String... params) {
            String result = "NOT OK";

            // here we make the request to the cloud server for MAXPOSITION number of coordinates
            try {
                thingsee = new ThingSee(username, password);

                JSONArray events = thingsee.Events(thingsee.Devices(), MAXPOSITIONS);
                //System.out.println(events);
                coordinates = thingsee.getPath(events);

//                for (Location coordinate: coordinates)
//                    System.out.println(coordinate);
                result = "OK";
            } catch (Exception e) {
                Log.d("NET", "Communication error: " + e.getMessage());
            }

            return result;
        }

        @Override
        protected void onPostExecute(String result) {
            // check that the background communication with the client was successfull
            if (result.equals("OK")) {
                // now the coordinates variable has those coordinates
                // elements of these coordinates is the Location object who has
                // fields for longitude, latitude and time when the position was fixed
                for (int i = 0; i < coordinates.size(); i++) {
                    Location loc = coordinates.get(i);

                    positions[i] = (new Date(loc.getTime())) +
                            " (" + loc.getLatitude() + "," +
                            loc.getLongitude() + ")"; //coordinates.get(i).toString();
                }
                //make array that contains latitude values
                for (int i = 0; i < coordinates.size(); i++) {
                    Location loc = coordinates.get(i);
                    bmtlat[i] = loc.getLatitude();
                }
                //make array that contains longitude values
                for (int i = 0; i < coordinates.size(); i++) {
                    Location loc = coordinates.get(i);
                    bmtlong[i] = loc.getLongitude();
                }
                //array that contains distances between the lat and lon values
                for (int i = 0; i < bmtlat.length - 1; i++) { //instead of coordinates.size we use bmtlat.length
                    bmtdist[i] = //plane approximation formula to get the distance between two points
                            Math.sqrt(Math.pow((40075d / 360d) * (bmtlat[i +1] - bmtlat[i]), 2d)
                                    + Math.pow(((40075d * Math.cos(Math.toDegrees(bmtlat[i])))/360) * (bmtlong[i+1] - bmtlong[i]), 2d));
                }
                //add bmtdist values together in a loop
                for (int i = 0; i < bmtdist.length; i++) {
                    //get rid of the cases where the value would mess up the totaldist calculation by adding 0
                    if (bmtlat[i] != 0 && bmtlat[i + 1] != 0 && bmtlong[i] != 0 && bmtlong[i + 1] != 0) {
                        //calculate the totaldist
                        totaldist = totaldist + bmtdist[i];
                    }
                }
                //round the totaldist to 3 decimals to show km at meter accuracy
                finaldist = (double) Math.round(totaldist * 1000) / 1000 ;

                //create an array that only contains date information of all data points
                for (int i = 0; i < bmtlat.length; i++){
                    Location loc = coordinates.get(i);
                    bmttime[i] = new Date(loc.getTime());
                }

                //create the 3 text views (distance, time and speed) to display to user
                TextView distanceV = (TextView) findViewById(R.id.distance);
                TextView timetakenV = (TextView) findViewById(R.id.timetaken);
                TextView averagespeedV = (TextView) findViewById(R.id.averagespeed);

                //temporary variables to make things easier to read
                String seconds = new String(Long.toString(getDateDiff(bmttime[bmttime.length - 1], bmttime[0], TimeUnit.SECONDS)));
                Double roundnumber = new Double(finaldist/(Double.parseDouble(seconds)/3600));

                //displays distance to user
                String superfinaldistance = new String("Distance: " + finaldist + "km" );

                //calculates total time taken. time is given in seconds,
                //divide by 60 to get minutes, modulus 60 to get leftover seconds
                String timetaken = new String ("Time: " + Long.toString(getDateDiff(bmttime[bmttime.length - 1], bmttime[0], TimeUnit.SECONDS)/60) + "m " +
                        (Long.toString(getDateDiff(bmttime[bmttime.length - 1], bmttime[0], TimeUnit.SECONDS)%60) + "s"));

                //displays average speed, rounded to 1 decimal point
                String finalaveragespeed = new String("Avg. Speed: " + (double)Math.round(roundnumber*10)/10 + "km/h");

                //set texts to textviews
                distanceV.setText(superfinaldistance);
                timetakenV.setText(timetaken);
                averagespeedV.setText(finalaveragespeed);
            } else {
                // no, tell that to the user and ask a new username/password pair
                positions[0] = getResources().getString(R.string.no_connection);
                queryDialog(MainActivity.this, getResources().getString(R.string.info_prompt));
            }
            //myAdapter.notifyDataSetChanged();
        }

        @Override
        protected void onPreExecute() {
            // first clear the previous entries (if they exist)
            for (int i = 0; i < positions.length; i++)
                positions[i] = "";
            //myAdapter.notifyDataSetChanged();
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
        }
    }

    /**
     * Manipulates the map when it's available.
     * The API invokes this callback when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera.
     * If Google Play services is not installed on the device, the user receives a prompt to install
     * Play services inside the SupportMapFragment. The API invokes this method after the user has
     * installed Google Play services and returned to the app.
     */

    public void onMapReady(GoogleMap googleMap) {

        if (bmtlat[0] != 0 && bmtlong[0] != 0) //set these as invalid values to avoid marking position 0,0 on run
        {
            //Make arraylist that contains the lat and long values in order to draw a line and set the markers
            ArrayList<LatLng> BMTmaplist = new ArrayList<LatLng>();
            for (int i=0; i<MAXPOSITIONS-1; i++){
                BMTmaplist.add(new LatLng(bmtlat[i], bmtlong[i]));
            }
            //set a end and start marker based on the arraylist
            LatLng end = BMTmaplist.get(0);
            LatLng start = BMTmaplist.get(BMTmaplist.size()-1);

            //Clear all previous entries
            googleMap.clear();
            //Add markers at end and start with start/end titled that show up when clicked
            googleMap.addMarker(new MarkerOptions().position(start)).setTitle("Start"); ;
            googleMap.addMarker(new MarkerOptions().position(end)).setTitle("End");
            //Default zoom level of the map
            googleMap.moveCamera(CameraUpdateFactory.zoomTo(15));
            //Center map on start coordinates
            googleMap.moveCamera(CameraUpdateFactory.newLatLng(start));

            //Draws a line between all the points found in BMTmaplist array and adds it to the map
            PolylineOptions line = new PolylineOptions().addAll(BMTmaplist).width(8).color(Color.RED);
            googleMap.addPolyline(line);

        }
    }
    //calculating elapsed time between 2 timestamps
    //input is 2 dates and timeunit in which result is returned in
    public static long getDateDiff(Date d1, Date d2, TimeUnit timeUnit) {
        long diffInMillies = d2.getTime() - d1.getTime();
        return timeUnit.convert(diffInMillies,TimeUnit.MILLISECONDS);
    }


}
